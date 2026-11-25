#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""The vector map's regions for Buteo's map server (mobile/docs/DECISIONS.md D31).

Buteo downloads the regions a flight reaches from an index, regions.json,
next to the region files.  The regions must be cut from ONE build of the
map, so that a tile on two regions' border is the same in both:

1. Build the country (or Europe) once, to zoom 11, with Planetiler:

     java -Xmx8g -jar planetiler.jar --download --area=france --maxzoom=11 \\
       --languages=fr,en,de,it --only-layers=water,waterway,landcover,landuse,\\
     transportation,boundary,place,mountain_peak --output=france.pmtiles

2. Cut each region along its outline, e.g. Geofabrik's .poly files
   (https://download.geofabrik.de/europe/france/rhone-alpes.poly):

     map_regions.py geojson rhone-alpes.poly > rhone-alpes.geojson
     pmtiles extract france.pmtiles out/rhone-alpes.pmtiles \\
       --region=rhone-alpes.geojson

3. Write the index of the folder (its .pmtiles files, with the .poly
   outline of the same name where there is one):

     map_regions.py index out --titles titles.json > out/regions.json

   titles.json, optional: {"rhone-alpes": "Rhône-Alpes", ...}.

Publish the folder as it is: the index names the files relative to itself.
"""
import argparse
import gzip
import hashlib
import json
import os
import struct
import sys

# an outline in the index: enough to choose the regions of a task
MAX_OUTLINE_POINTS = 200


def read_poly(path):
    """The first ring of an Osmosis .poly file, [[lon, lat], ...]."""
    ring = []
    with open(path) as f:
        lines = f.read().split('\n')[2:]
    for line in lines:
        parts = line.split()
        if parts == ['END']:
            break
        if len(parts) == 2:
            ring.append([float(parts[0]), float(parts[1])])
    if len(ring) < 3:
        raise ValueError(f'{path}: no outline')
    return ring


def simplify(ring, n=MAX_OUTLINE_POINTS):
    """At most n points of the ring, evenly spread."""
    if len(ring) <= n:
        return ring
    step = len(ring) / n
    return [ring[int(i * step)] for i in range(n)]


def pmtiles_info(path):
    """Bounds and the OpenStreetMap data's day of a PMTiles v3 file."""
    with open(path, 'rb') as f:
        header = f.read(127)
        if header[:7] != b'PMTiles' or header[7] != 3:
            raise ValueError(f'{path}: not PMTiles version 3')
        meta_offset, meta_length = struct.unpack_from('<2Q', header, 24)
        f.seek(meta_offset)
        meta = f.read(meta_length)
    if header[97] == 2:
        meta = gzip.decompress(meta)
    elif header[97] not in (0, 1):
        raise ValueError(f'{path}: metadata compressed other than by gzip')
    metadata = json.loads(meta or b'{}')
    date = str(metadata.get('planetiler:osm:osmosisreplicationtime', ''))[:10]
    bounds = [round(v / 1e7, 5) for v in struct.unpack_from('<4i', header, 102)]
    return bounds, date or None


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
        poly = os.path.join(folder, region + '.poly')
        if os.path.exists(poly):
            entry['outline'] = [[round(x, 4), round(y, 4)] for x, y in simplify(read_poly(poly))]
        regions.append(entry)
    return {'regions': regions}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('index', help='write regions.json for a folder of regions')
    p.add_argument('folder')
    p.add_argument('--titles', help='JSON file: region name -> title')
    p = sub.add_parser('geojson', help='a .poly outline as GeoJSON, for pmtiles extract')
    p.add_argument('poly')
    args = parser.parse_args()

    if args.command == 'geojson':
        ring = read_poly(args.poly)
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        json.dump({'type': 'Polygon', 'coordinates': [ring]}, sys.stdout)
    else:
        titles = {}
        if args.titles:
            with open(args.titles) as f:
                titles = json.load(f)
        json.dump(index(args.folder, titles), sys.stdout, ensure_ascii=False,
                  separators=(',', ':'))
    sys.stdout.write('\n')


if __name__ == '__main__':
    main()
