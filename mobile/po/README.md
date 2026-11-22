# Buteo's translations

Buteo's own texts, in gettext catalogues (mobile/docs/DECISIONS.md D28).
Texts XCSoar has too (the same English) are translated in XCSoar's
`po/`; the build takes both into Android's `values-<lang>/strings.xml`.

- `buteo.pot`: the template, generated from
  `mobile/app/src/main/res/values/strings.xml`
- `<lang>.po`: a language, named as in `po/` (`fr`, `de`, `pt_BR`…)

After changing the English (`values/strings.xml`), from the repository
root (needs gettext's `msgmerge`):

    mobile/tools/po2strings.py pot mobile/app/src/main/res/values/strings.xml \
        --po-dir po --app-po-dir mobile/po

It rewrites `buteo.pot` and merges it into each `<lang>.po`: new texts
come empty, removed ones go.  Fill the empty `msgstr` (French must be
complete: `TranslationsTest`), then check with
`msgfmt --check -o /dev/null mobile/po/<lang>.po`.

A new language: copy `buteo.pot` to `<lang>.po`, add `"Language:
<lang>\n"` to its header, and translate.  Keep the placeholders
(`%1$s`, `%2$d`) of the English: a translation with others is left out.
Brand names and ICAO abbreviations stay as they are (`doc/i18n.rst`).
