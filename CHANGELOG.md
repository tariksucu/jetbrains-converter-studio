# Changelog

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
