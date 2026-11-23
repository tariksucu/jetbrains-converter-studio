<p align="center">
  <a href="https://converter.studio"><img src="src/main/resources/META-INF/pluginIcon.svg" width="80" height="80" alt="Converter Studio"></a>
</p>

# Converter Studio for JetBrains IDEs

Convert files from the Project view or an editor tab, in IntelliJ IDEA, PyCharm, WebStorm,
GoLand, PhpStorm, Rider, Android Studio and every other IDE built on the IntelliJ Platform.
Right-click one or more files, choose **Convert with Converter Studio…**, pick a format, and the
result is saved next to the original. Conversions run **on your computer first**; the
[Converter Studio](https://converter.studio) desktop app and cloud are there when you choose them.

## Where each conversion runs

The dialog lists the formats the selected files can become and, for each one, where it would run.
It always starts with this computer when it can.

| Route                    | When                                         | What happens to the file                                                                                                                             |
| ------------------------ | -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| **On this computer**     | Always offered when a recipe below can do it | Stays on your computer. The dialog names the program that will run, and where it is.                                                                 |
| **Converter Studio app** | Only if you turn it on in the settings       | Handed to [Converter Studio for desktop](https://converter.studio) on this computer; the app opens with the files and the format, you press Convert. |
| **Cloud (uses credits)** | Only when you pick it, with an API key       | Uploaded to converter.studio over HTTPS, converted, and deleted (the upload after the conversion, the result after 6 hours). Uses credits.           |

A conversion that fails on your computer never moves to the cloud by itself: the notification
offers **Try in the cloud (uses credits)**, which asks before anything is uploaded.

### On this computer

| Done by                                                 | Converts                                                                                             |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| The plugin itself (no program needed)                   | JSON to CSV and back, JSON or CSV to YAML, SRT and VTT subtitles, PNG, JPG, BMP, GIF and TIFF images |
| [FFmpeg](https://ffmpeg.org)                            | Video to MP4, WebM, MOV, MKV, AVI or GIF; video or audio to MP3, M4A, WAV, FLAC, AAC, OGG, Opus      |
| [LibreOffice](https://www.libreoffice.org)              | Word, Excel, PowerPoint, OpenDocument, RTF, HTML and CSV to PDF and to each other                    |
| [Pandoc](https://pandoc.org)                            | Markdown, HTML, DOCX, ODT, EPUB and RTF to each other (Markdown and EPUB to PDF with LibreOffice)    |
| [ImageMagick](https://imagemagick.org)                  | Images to WebP, AVIF, ICO and PDF; HEIC and RAW photos when your ImageMagick reads them              |
| [Ghostscript](https://ghostscript.com)                  | Smaller PDFs, EPS to PDF                                                                             |
| [Tesseract](https://github.com/tesseract-ocr/tesseract) | Text recognition (OCR) of images, and of scanned PDFs with Ghostscript                               |

The plugin finds these programs on your PATH and in their usual install folders (Homebrew,
`/Applications`, `C:\Program Files`, winget, Chocolatey, Scoop…). **Settings | Tools | Converter
Studio** shows what it found and lets you type another path. ImageMagick is asked which formats
it reads and writes, FFmpeg which encoders it has, and Tesseract which languages are installed, so
the dialog only offers what will work. Programs run with a time limit and stop when you press
Cancel; nothing goes through a shell.

### Converter Studio app

Turn on **Use the Converter Studio desktop app when installed** to add the app as a route. The
plugin opens a `converter-studio://convert` link with the files and the format; Converter Studio
for desktop (version 0.1.11 or later) shows the files, with the format chosen, and waits for you
to press Convert. The app converts on this computer and can do conversions your installed
programs cannot. Older app versions only come to the front. Not offered in remote development
sessions or for files inside WSL. If you installed the app as a Snap or Flatpak, it can only open
files in your home folder.

### Cloud (uses credits)

For formats your computer cannot convert (HEIC without a suitable ImageMagick, RAW photos,
e-books, fonts and more), add a Converter Studio API key:

1. Make a free account at [converter.studio](https://converter.studio) and create an API key on
   your [account page](https://converter.studio/account#api-keys).
2. Paste it in **Settings | Tools | Converter Studio** (**Check Key** shows your plan and credits).
   It is kept in the IDE's password safe, never in settings files.
3. Choose **Cloud (uses credits)** in the dialog; its button reads **Upload and Convert**.

Most image and document conversions cost 1 credit; long videos cost more
([pricing](https://converter.studio/pricing)). A conversion that costs more than your limit
(**Max credits per conversion**, 20 by default; 0 asks every time) asks before it starts. A free
account gets free credits every day.

## Settings

**Settings | Tools | Converter Studio**:

- Paths of FFmpeg, LibreOffice, Pandoc, ImageMagick, Ghostscript and Tesseract (empty: found
  automatically), with what was found, and **Detect Again**
- OCR languages (Tesseract's, such as `eng` or `eng+deu`)
- Use the Converter Studio desktop app when installed (off by default)
- API key, API address (`https://converter.studio/api`; HTTPS only), max credits per conversion

## Privacy

- No telemetry. The plugin sends nothing anywhere for local conversions.
- With an API key or the app turned on, the dialog reads Converter Studio's public list of formats
  (no key, no file) from the API address, at most once an hour.
- A cloud conversion sends the file and your API key only to the API address in the settings, over
  HTTPS; the key is never logged. Requests name the plugin and its version
  (`X-Converter-Studio-Client`) so Converter Studio can count conversions by integration.
- See the [privacy policy](https://converter.studio/privacy).

## Requirements

An IDE built on IntelliJ Platform 2026.2 or later. The programs above are optional; install the
ones you need.

## Building

`./gradlew buildPlugin` (the plugin zip in `build/distributions`), `./gradlew test`,
`./gradlew verifyPlugin`. Gradle uses a JDK 25 toolchain and downloads one if needed.

## Support

Questions and bug reports: [GitHub issues](https://github.com/tariksucu/jetbrains-converter-studio/issues)
or support@converter.studio. The API is documented at
[converter.studio/developers](https://converter.studio/developers).

## License

[MIT](LICENSE), © Converter Studio.
