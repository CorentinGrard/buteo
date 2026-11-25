#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""The vector map's regions for Buteo's map server (mobile/docs/DECISIONS.md D31).

Buteo downloads the regions a flight reaches from an index, regions.json,
next to the region files.  The regions are cut from ONE map of the planet,
Protomaps' daily build (https://build.protomaps.com, schema 4), so that a
tile on two regions' border is the same in both and the map has no seam.
Only the tiles a region needs are read, by HTTP ranges:

1. The regions' outlines, from Geofabrik's index (the extracts that have
   no smaller ones inside), e.g. every region of Europe:

     map_regions.py geofabrik index-v1.json --under europe --out work

   writes work/<name>.geojson and work/titles.json.

2. Each region's tiles, to zoom 11, every tile that touches its outline:

     map_regions.py cut https://build.protomaps.com/20261125.pmtiles \
       work/france-rhone-alpes.geojson out/france-rhone-alpes.pmtiles

3. The index of the folder, with each region's outline:

     cp work/*.geojson out/ && \
     map_regions.py index out --titles work/titles.json > out/regions.json

   Several indexes (one a continent) become one with "merge".

The monthly GitHub Actions workflow .github/workflows/map-regions.yml does
all of it and publishes the files and regions.json in the release "maps".
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import math
import os
import struct
import sys
import threading
import time
import urllib.error
import urllib.request

# the outlines in the index: enough to choose the regions of a task
MAX_OUTLINE_POINTS = 200
MAX_OUTLINE_RINGS = 12
# the deepest zoom of the regions: a glider's map stays at 11 or wider
MAX_ZOOM = 11
# what a region keeps of Protomaps' tiles (85 % of them is land use, mostly
# fields and meadows the map does not draw): no buildings, and these kinds
# of land use
DROP_LAYERS = {'buildings'}
LANDUSE_KINDS = {
    'forest', 'wood', 'glacier', 'residential', 'industrial', 'commercial',
    'bare_rock', 'scrub', 'sand', 'beach', 'wetland', 'aerodrome', 'runway',
    'taxiway', 'military', 'nature_reserve', 'national_park', 'protected_area',
}
# ranges of a remote file read as one when closer than this
RANGE_GAP = 256 * 1024
RANGE_MAX = 16 * 1024 * 1024
# the specification's: the header and the root directory within 16 KiB
ROOT_MAX = 16384 - 127
HEADER = struct.Struct('<7sB11QBBBBBB4iB2i')


def read_outline(path):
    """The outer rings of a region, [[[lon, lat], ...], ...]: an Osmosis
    .poly file (holes, "!" sections, left out: the region keeps the tiles
    inside) or GeoJSON (a Polygon, MultiPolygon or Feature)."""
    if path.endswith('.poly'):
        return read_poly(path)
    with open(path) as f:
        g = json.load(f)
    if g.get('type') == 'Feature':
        g = g['geometry']
    polygons = ([g['coordinates']] if g['type'] == 'Polygon'
                else g['coordinates'] if g['type'] == 'MultiPolygon' else [])
    rings = [[[float(c[0]), float(c[1])] for c in poly[0]] for poly in polygons if poly]
    rings = [r for r in rings if len(r) >= 3]
    if not rings:
        raise ValueError(f'{path}: no outline')
    return rings


def read_poly(path):
    rings = []
    with open(path) as f:
        lines = [line.strip() for line in f.read().split('\n')[1:]]
    ring, hole = None, False
    for line in lines:
        if ring is None:
            if line and line != 'END':
                ring, hole = [], line.startswith('!')
            continue
        if line == 'END':
            if not hole and len(ring) >= 3:
                rings.append(ring)
            ring = None
            continue
        parts = line.split()
        if len(parts) == 2:
            ring.append([float(parts[0]), float(parts[1])])
    if not rings:
        raise ValueError(f'{path}: no outline')
    return rings


def outlines(rings):
    """The largest rings (islands too), with MAX_OUTLINE_POINTS points
    shared among them by size, evenly spread along each."""
    rings = sorted(rings, key=len, reverse=True)[:MAX_OUTLINE_RINGS]
    total = sum(len(r) for r in rings)
    out = []
    for r in rings:
        n = max(4, MAX_OUTLINE_POINTS * len(r) // total)
        step = max(1, len(r) / n)
        out.append([[round(r[int(i * step)][0], 4), round(r[int(i * step)][1], 4)]
                    for i in range(min(n, len(r)))])
    return out


# --- PMTiles version 3 -------------------------------------------------

def tile_id(z, x, y):
    """The specification's tile ID: the zooms before, then a Hilbert curve."""
    acc = (4 ** z - 1) // 3
    n = 1 << z
    s = n >> 1
    d = 0
    while s > 0:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x, y = n - 1 - x, n - 1 - y
            x, y = y, x
        s >>= 1
    return acc + d


def read_varint(b, i):
    value = shift = 0
    while True:
        c = b[i]
        i += 1
        value |= (c & 0x7F) << shift
        shift += 7
        if c < 0x80:
            return value, i


def write_varint(out, v):
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def decode_directory(b):
    """[(tile ID, run length, offset, length), ...]"""
    n, i = read_varint(b, 0)
    ids, runs, lengths, offsets = [], [], [], []
    last = 0
    for _ in range(n):
        d, i = read_varint(b, i)
        last += d
        ids.append(last)
    for _ in range(n):
        v, i = read_varint(b, i)
        runs.append(v)
    for _ in range(n):
        v, i = read_varint(b, i)
        lengths.append(v)
    for k in range(n):
        v, i = read_varint(b, i)
        offsets.append(offsets[k - 1] + lengths[k - 1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, runs, offsets, lengths))


def encode_directory(entries):
    out = bytearray()
    write_varint(out, len(entries))
    last = 0
    for e in entries:
        write_varint(out, e[0] - last)
        last = e[0]
    for e in entries:
        write_varint(out, e[1])
    for e in entries:
        write_varint(out, e[3])
    for k, e in enumerate(entries):
        before = entries[k - 1] if k > 0 else None
        write_varint(out, 0 if before and before[2] + before[3] == e[2] else e[2] + 1)
    return gzip.compress(bytes(out), mtime=0)


class Source:
    """A local file, or a URL read by HTTP ranges (with retries)."""

    def __init__(self, path):
        self.url = path if path.startswith(('https://', 'http://')) else None
        self.fd = None if self.url else os.open(path, os.O_RDONLY)

    def read(self, offset, length):
        if self.fd is not None:
            return os.pread(self.fd, length, offset)
        for attempt in range(6):
            try:
                request = urllib.request.Request(self.url, headers={
                    'Range': f'bytes={offset}-{offset + length - 1}',
                    'User-Agent': 'map-regions'})
                with urllib.request.urlopen(request, timeout=120) as r:
                    data = r.read()
                if len(data) != length:
                    raise IOError(f'{len(data)} bytes instead of {length}')
                return data
            except (OSError, urllib.error.URLError) as e:
                if attempt == 5:
                    raise
                print(f'{self.url} {offset}+{length}: {e}, again', file=sys.stderr)
                time.sleep(2 ** attempt)


class PMTiles:
    """A PMTiles version 3 file, local or remote, read as needed."""

    def __init__(self, path):
        self.source = Source(path)
        first = self.source.read(0, 16384)
        h = HEADER.unpack_from(first)
        if h[0] != b'PMTiles' or h[1] != 3:
            raise ValueError(f'{path}: not PMTiles version 3')
        self.header = h
        (self.root_offset, self.root_length, self.meta_offset, self.meta_length,
         self.leaf_offset, self.leaf_length, self.tile_offset, self.tile_length) = h[2:10]
        self.internal_compression = h[14]
        self.min_zoom, self.max_zoom = h[17], h[18]
        self.bounds = [v / 1e7 for v in h[19:23]]
        if self.internal_compression not in (1, 2):
            raise ValueError(f'{path}: directories compressed other than by gzip')
        self.leaves = {}
        self.lock = threading.Lock()
        end = self.root_offset + self.root_length
        root = first[self.root_offset:end] if end <= len(first) \
            else self.source.read(self.root_offset, self.root_length)
        self.root = decode_directory(self.inflate(root))

    def inflate(self, b):
        return gzip.decompress(b) if self.internal_compression == 2 else b

    def metadata(self):
        return json.loads(self.inflate(self.source.read(self.meta_offset, self.meta_length))
                          or b'{}')

    def leaf(self, offset, length):
        with self.lock:
            if (offset, length) in self.leaves:
                return self.leaves[(offset, length)]
        d = decode_directory(self.inflate(self.source.read(self.leaf_offset + offset, length)))
        with self.lock:
            self.leaves[(offset, length)] = d
        return d

    def find(self, z, x, y):
        """(offset, length) of the tile's data within the tile section, or None."""
        t = tile_id(z, x, y)
        entries = self.root
        for _ in range(4):
            lo, hi = 0, len(entries) - 1
            while lo <= hi:
                k = (lo + hi) // 2
                if entries[k][0] < t:
                    lo = k + 1
                elif entries[k][0] > t:
                    hi = k - 1
                else:
                    hi = k
                    break
            if hi < 0:
                return None
            e = entries[hi]
            if e[1] == 0:  # a leaf directory from its ID on
                entries = self.leaf(e[2], e[3])
                continue
            return (e[2], e[3]) if t - e[0] < e[1] else None
        return None

    def fetch(self, ranges):
        """{offset: data} for [(offset, length)] of the tile section: close
        ranges read together, several reads at a time."""
        groups = []
        for offset, length in sorted(set(ranges)):
            g = groups[-1] if groups else None
            if g and offset - g[1] <= RANGE_GAP and offset + length - g[0] <= RANGE_MAX:
                g[1] = max(g[1], offset + length)
                g[2].append((offset, length))
            else:
                groups.append([offset, offset + length, [(offset, length)]])

        def read(g):
            data = self.source.read(self.tile_offset + g[0], g[1] - g[0])
            return {o: data[o - g[0]:o - g[0] + n] for o, n in g[2]}

        out = {}
        # several regions are cut at a time: a few reads each
        with concurrent.futures.ThreadPoolExecutor(4) as pool:
            for part in pool.map(read, groups):
                out.update(part)
        return out


def fields(b):
    """The protobuf fields of b: (number, wire type, value, start, end);
    value: an int, or the bytes of a length-delimited field."""
    i = 0
    while i < len(b):
        start = i
        key, i = read_varint(b, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(b, i)
        elif wire == 2:
            n, i = read_varint(b, i)
            value, i = b[i:i + n], i + n
        elif wire == 1:
            value, i = b[i:i + 8], i + 8
        elif wire == 5:
            value, i = b[i:i + 4], i + 4
        else:
            raise ValueError(f'wire type {wire}')
        yield number, wire, value, start, i


def slim_layer(layer, kinds):
    """A vector tile layer without the features whose "kind" is not in kinds."""
    keys = [v.decode() for n, w, v, *_ in fields(layer) if n == 3]
    values = []
    for n, w, v, *_ in fields(layer):
        if n == 4:
            string = [x for m, _, x, *_ in fields(v) if m == 1]
            values.append(string[0].decode(errors='replace') if string else None)
    if 'kind' not in keys:
        return layer
    kind = keys.index('kind')
    out = bytearray()
    for n, w, v, start, end in fields(layer):
        if n == 2:
            tags = []
            for m, _, x, *_ in fields(v):
                if m == 2:
                    j = 0
                    while j < len(x):
                        t, j = read_varint(x, j)
                        tags.append(t)
            pairs = dict(zip(tags[::2], tags[1::2]))
            if kind in pairs and values[pairs[kind]] not in kinds:
                continue
        out += layer[start:end]
    return bytes(out)


def slim(tile):
    """A gzipped vector tile with what the regions keep of it."""
    raw = gzip.decompress(tile)
    out = bytearray()
    for n, w, layer, start, end in fields(raw):
        if n != 3 or w != 2:
            out += raw[start:end]
            continue
        name = next((v.decode() for m, _, v, *_ in fields(layer) if m == 1), None)
        if name in DROP_LAYERS:
            continue
        if name == 'landuse':
            layer = slim_layer(layer, LANDUSE_KINDS)
        out.append(0x1A)
        write_varint(out, len(layer))
        out += layer
    return gzip.compress(bytes(out), compresslevel=9, mtime=0)


def write_pmtiles(path, source, tiles, bounds, max_zoom, metadata):
    """tiles: [(tile ID, data)] sorted by ID.  Identical data is stored
    once, neighbours with it as runs."""
    data = bytearray()
    stored = {}  # digest -> offset here
    entries = []
    for t, tile in tiles:
        digest = hashlib.sha1(tile).digest()
        if entries and entries[-1][0] + entries[-1][1] == t and stored.get(digest) == entries[-1][2]:
            e = entries[-1]
            entries[-1] = (e[0], e[1] + 1, e[2], e[3])
            continue
        if digest not in stored:
            stored[digest] = len(data)
            data += tile
        entries.append((t, 1, stored[digest], len(tile)))

    # the root alone, else leaves of a size that keeps the root small
    root = encode_directory(entries)
    leaves = b''
    leaf_size = 4096
    while len(root) > ROOT_MAX:
        leaves = bytearray()
        root_entries = []
        for i in range(0, len(entries), leaf_size):
            chunk = entries[i:i + leaf_size]
            leaf = encode_directory(chunk)
            root_entries.append((chunk[0][0], 0, len(leaves), len(leaf)))
            leaves += leaf
        root = encode_directory(root_entries)
        leaf_size *= 2
    meta = gzip.compress(json.dumps(metadata).encode(), mtime=0)

    root_offset = 127
    meta_offset = root_offset + len(root)
    leaf_offset = meta_offset + len(meta)
    tile_offset = leaf_offset + len(leaves)
    s = source.header
    header = HEADER.pack(
        b'PMTiles', 3, root_offset, len(root), meta_offset, len(meta),
        leaf_offset, len(leaves), tile_offset, len(data),
        sum(e[1] for e in entries), len(entries), len(stored),
        1, 2,  # clustered, gzip directories
        s[15], s[16],  # the source's tile compression and type
        source.min_zoom, max_zoom,
        *[round(v * 1e7) for v in bounds],
        # the centre: the region's
        min(s[23], max_zoom), round((bounds[0] + bounds[2]) / 2 * 1e7),
        round((bounds[1] + bounds[3]) / 2 * 1e7))
    with open(path + '.part', 'wb') as f:
        f.write(header + root + meta + leaves + data)
    os.replace(path + '.part', path)
    return sum(e[1] for e in entries), len(header) + len(root) + len(meta) + len(leaves) + len(data)


# --- cutting -----------------------------------------------------------

def tile_x(lon, z):
    return (lon + 180) / 360 * (1 << z)


def tile_y(lat, z):
    lat = max(min(lat, 85.05), -85.05)
    r = math.radians(lat)
    return (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * (1 << z)


def touched_tiles(rings, z):
    """Every tile of zoom z the rings' area touches: the tiles along
    their edges (sampled every 1/32 tile) and those between them."""
    n = 1 << z
    tiles = set()
    for ring in rings:
        pts = [(tile_x(lon, z), tile_y(lat, z)) for lon, lat in ring]
        for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
            steps = max(1, int(max(abs(x2 - x1), abs(y2 - y1)) * 32))
            for i in range(steps + 1):
                t = i / steps
                x, y = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
                tiles.add((min(int(x), n - 1), min(int(y), n - 1)))
        # inside: each row's middle, between pairs of crossings
        ys = [p[1] for p in pts]
        for row in range(int(min(ys)), min(int(max(ys)), n - 1) + 1):
            c = row + 0.5
            xs = sorted(x1 + (c - y1) * (x2 - x1) / (y2 - y1)
                        for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1])
                        if (y1 > c) != (y2 > c))
            for a, b in zip(xs[::2], xs[1::2]):
                for col in range(int(a), min(int(b), n - 1) + 1):
                    tiles.add((col, row))
    return tiles


def cut(source_path, outline_path, out_path, max_zoom=MAX_ZOOM):
    source = PMTiles(source_path)
    rings = read_outline(outline_path)
    max_zoom = min(max_zoom, source.max_zoom)
    tiles = []
    for z in range(source.min_zoom, max_zoom + 1):
        for x, y in touched_tiles(rings, z):
            found = source.find(z, x, y)
            if found:
                tiles.append((tile_id(z, x, y), *found))
    tiles.sort()
    data_of = source.fetch((o, n) for _, o, n in tiles)
    slimmed = {o: slim(d) for o, d in data_of.items()}
    lons = [p[0] for r in rings for p in r]
    lats = [p[1] for r in rings for p in r]
    bounds = [max(min(lons), source.bounds[0]), max(min(lats), source.bounds[1]),
              min(max(lons), source.bounds[2]), min(max(lats), source.bounds[3])]
    metadata = source.metadata()
    metadata['vector_layers'] = [v for v in metadata.get('vector_layers', [])
                                 if v.get('id') not in DROP_LAYERS]
    metadata['buteo:landuse'] = sorted(LANDUSE_KINDS)
    return write_pmtiles(out_path, source, [(t, slimmed[o]) for t, o, _ in tiles],
                         bounds, max_zoom, metadata)


# --- the regions and the index -------------------------------------------

def geofabrik(index_path, under, out):
    """The extracts under `under` (any depth) with none inside them, as
    <name>.geojson in out, and titles.json.  A name is the extract's path
    below its continent, whatever `under` is: "france-rhone-alpes",
    "us-alabama" (Geofabrik's "us/alabama")."""
    with open(index_path) as f:
        features = {x['properties']['id']: x for x in json.load(f)['features']}
    parents = {x['properties'].get('parent') for x in features.values()}
    leaves = [i for i, x in features.items() if i not in parents and x.get('geometry')]
    # extracts that join others ("alps", "dach"...), which a task there
    # would download besides them: at least two other extracts inside
    # (by their middles), making up 30 % of the area or more (Hebei
    # around Beijing and Tianjin is no composite)
    middles = {i: middle(features[i]['geometry']) for i in leaves}
    areas = {i: area(features[i]['geometry']) for i in leaves}
    # the largest first: "south-africa-and-lesotho" is the composite, not
    # "south-africa" inside it
    composite = set()
    for i in sorted(leaves, key=areas.get, reverse=True):
        inside = [j for j in leaves if j != i and j not in composite and
                  inside_geometry(features[i]['geometry'], middles[j])]
        if len(inside) >= 2 and sum(areas[j] for j in inside) >= 0.3 * areas[i]:
            composite.add(i)

    def path(i):
        p = features[i]['properties'].get('parent')
        return (path(p) + [i]) if p else [i]

    os.makedirs(out, exist_ok=True)
    titles = {}
    for i, x in sorted(features.items()):
        if i not in leaves or i in composite or under not in path(i):
            continue
        name = '-'.join(p.split('/')[-1] for p in path(i)[1:]) or i
        titles[name] = x['properties']['name']
        with open(os.path.join(out, name + '.geojson'), 'w') as f:
            json.dump(x['geometry'], f)
    with open(os.path.join(out, 'titles.json'), 'w') as f:
        json.dump(titles, f, ensure_ascii=False, indent=0)
    return titles


def middle(geometry):
    """A point inside the geometry's largest ring: the middle of its
    longest crossing along the ring's middle latitude."""
    polygons = [geometry['coordinates']] if geometry['type'] == 'Polygon' \
        else geometry['coordinates']
    ring = max((p[0] for p in polygons), key=len)
    lats = [c[1] for c in ring]
    y = (min(lats) + max(lats)) / 2
    xs = sorted(a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
                for a, b in zip(ring, ring[1:] + ring[:1]) if (a[1] > y) != (b[1] > y))
    pairs = list(zip(xs[::2], xs[1::2])) or [(ring[0][0], ring[0][0])]
    a, b = max(pairs, key=lambda p: p[1] - p[0])
    return (a + b) / 2, y


def area(geometry):
    """The outer rings' area, square degrees of latitude (a plane each)."""
    polygons = [geometry['coordinates']] if geometry['type'] == 'Polygon' \
        else geometry['coordinates']
    total = 0
    for polygon in polygons:
        ring = polygon[0]
        k = math.cos(math.radians(sum(c[1] for c in ring) / len(ring)))
        total += abs(sum(a[0] * b[1] - b[0] * a[1]
                         for a, b in zip(ring, ring[1:] + ring[:1]))) / 2 * k
    return total


def inside_geometry(geometry, point):
    x, y = point
    polygons = [geometry['coordinates']] if geometry['type'] == 'Polygon' \
        else geometry['coordinates']
    for polygon in polygons:
        ring = polygon[0]
        c = False
        for a, b in zip(ring, ring[1:] + ring[:1]):
            if (a[1] > y) != (b[1] > y) and x < a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]):
                c = not c
        if c:
            return True
    return False


def pmtiles_info(path):
    """Bounds and the OpenStreetMap data's day of a PMTiles v3 file."""
    p = PMTiles(path)
    date = str(p.metadata().get('planetiler:osm:osmosisreplicationtime', ''))[:10]
    return [round(v, 5) for v in p.bounds], date or None


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def index(folder, titles):
    regions = []
    for name in sorted(os.listdir(folder)):
        if not name.endswith('.pmtiles'):
            continue
        region = name[:-len('.pmtiles')]
        path = os.path.join(folder, name)
        bbox, date = pmtiles_info(path)
        if date is None:
            raise ValueError(f'{path}: no date in the metadata (not from Planetiler?)')
        entry = {
            'name': region,
            'title': titles.get(region, region),
            'url': name,
            'size': os.path.getsize(path),
            'sha256': sha256(path),
            'date': date,
            'bbox': bbox,
        }
        for ext in ('.poly', '.geojson'):
            outline = os.path.join(folder, region + ext)
            if os.path.exists(outline):
                entry['outlines'] = outlines(read_outline(outline))
                break
        regions.append(entry)
    return {'regions': regions}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('geofabrik', help="the regions' outlines from Geofabrik's index")
    p.add_argument('index', help='index-v1.json from https://download.geofabrik.de')
    p.add_argument('--under', required=True, help='e.g. europe, or france')
    p.add_argument('--out', required=True)
    p = sub.add_parser('cut', help="a region's tiles out of a larger file or URL")
    p.add_argument('source')
    p.add_argument('outline', help='.poly or .geojson')
    p.add_argument('output')
    p.add_argument('--maxzoom', type=int, default=MAX_ZOOM)
    p = sub.add_parser('index', help='write regions.json for a folder of regions')
    p.add_argument('folder')
    p.add_argument('--titles', help='JSON file: region name -> title')
    p = sub.add_parser('merge', help='several regions.json as one')
    p.add_argument('indexes', nargs='+')
    p.add_argument('--previous', help="the published index: its regions the new ones "
                   "lack stay (a continent's job failed)")
    args = parser.parse_args()

    if args.command == 'geofabrik':
        titles = geofabrik(args.index, args.under, args.out)
        print(f'{len(titles)} regions', file=sys.stderr)
        return
    if args.command == 'cut':
        start = time.time()
        tiles, size = cut(args.source, args.outline, args.output, args.maxzoom)
        print(f'{args.output}: {tiles} tiles, {size} bytes, {time.time() - start:.0f} s',
              file=sys.stderr)
        return
    if args.command == 'merge':
        regions = []
        for path in args.indexes:
            with open(path) as f:
                regions += json.load(f)['regions']
        if args.previous and os.path.exists(args.previous):
            names = {r['name'] for r in regions}
            with open(args.previous) as f:
                regions += [r for r in json.load(f)['regions'] if r['name'] not in names]
        result = {'regions': sorted(regions, key=lambda r: r['name'])}
    else:
        titles = {}
        if args.titles:
            with open(args.titles) as f:
                titles = json.load(f)
        result = index(args.folder, titles)
    json.dump(result, sys.stdout, ensure_ascii=False, separators=(',', ':'))
    sys.stdout.write('\n')


if __name__ == '__main__':
    main()
