# Changelog

## 1.0.1

The plugin's own data conversions no longer lose or change data.

- CSV to JSON or YAML: every column gets a name of its own, checked against every other name. A
  header written twice (`id,id`) lost its first column, and a header `column2` with a row of two
  cells lost a value; now they give `id_2` and `column2_2`. Spaces around header names are not part
  of them.
- JSON to CSV or YAML: numbers are written as the file writes them. Integers beyond 2^63 and long
  decimals were rounded, and 1E400 stopped the conversion with an internal error.
- JSON to CSV: a key `a.b` next to a key `b` inside `a` wrote both into one column, one over the
  other; now the second is `a.b_2`.
- The CSV separator is the one the first line uses most outside quotes: comma, semicolon, tab or
  `|` (only commas were read before).
- An empty CSV file and an empty JSON list say there is nothing to convert, instead of writing an
  empty result.
- YAML: an object in a list starts on the dash's line (`- name: Ada`), as the other editors write
  it; control characters, DEL and the line and paragraph separators are escaped; text beyond ASCII
  is no longer quoted.
- A data file that is not UTF-8 stops with a message that says so.
- The four editor plugins (VS Code, JetBrains, Sublime Text, Neovim) now follow the same rules and run the same test cases, so a file gives the same result in each.

## 1.0.0

First release.

- **Convert with Converter Studio…** in the Project view's context menu (one or several files) and on
  editor tabs.
- A dialog lists the target formats, each with where it runs: **On this computer** (with the program
  that will do it), **Converter Studio app** (when turned on) or **Cloud (uses credits)**. This
  computer is the default whenever it can do the conversion.
- On this computer: the plugin's own code (JSON, CSV, YAML, SRT, VTT, PNG, JPG, BMP, GIF, TIFF) and
  FFmpeg, LibreOffice, Pandoc, ImageMagick, Ghostscript and Tesseract, found on PATH and in their
  usual install folders, or at a path set in the settings.
- The Converter Studio app: the files and the format are handed to Converter Studio for desktop
  0.1.11 or later, where you press Convert.
- The cloud: uploads to converter.studio with your API key (kept in the IDE's password safe), large
  files in parts, a question before a conversion above your credit limit.
- Background progress with Cancel; results next to the originals without overwriting
  (`photo (2).jpg`); a notification with Open and Show in folder; a failed local conversion offers
  "Try in the cloud (uses credits)", never automatically.
