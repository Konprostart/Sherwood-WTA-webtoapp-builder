# Privacy Policy

**Effective date:** October 24, 2026

This policy describes the **Sherwood WTA** Android app, an on-device builder. Sherwood WTA is an independent fork maintained by **Konprostart**. It covers the app you install from Google Play or from GitHub. It does not cover a website, server, or APK that you create with Sherwood WTA. If you publish one of those, it needs its own privacy policy.

Contact: [GitHub Issues](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues)

## What we do not do

Sherwood WTA has no account system. We do not run a server that stores your projects, and we do not ship an analytics or advertising SDK that reports how you use the builder back to us. We do not sell personal information.

## What stays on your device

The following stays in the app's private storage unless you export, share, or back it up yourself:

- Projects you create: names, URLs, icons, HTML and other project files, and settings
- Signing keys and the passwords you set for them
- AI provider API keys you enter
- Local backups you create inside the app

Delete a project in the app, or uninstall Sherwood WTA, to remove that copy from the device. Uninstalling does not delete files you already exported or shared.

## When data leaves the device

A feature sends data only when you use it, and it sends that data to the service that feature talks to. We do not receive a copy on a server we operate. The other service handles it under its own policy.

| You use | What is sent | Who receives it |
| --- | --- | --- |
| Update check, module catalog, and the default runtime downloads | The request itself, including your IP address | GitHub (`api.github.com`, `github.com`, `raw.githubusercontent.com`), jsDelivr (`cdn.jsdelivr.net`), and the download host for that runtime |
| Download of GitHub release assets when a mirror is enabled | The request for the file | The mirror service you selected (for example `gh-proxy.com`, `gh.zwy.one`, `gh.llkk.cc`), then GitHub |
| Preview, or a page you open in the built-in browser | The address you open, and whatever that site asks the browser to send | That website |
| Import or scrape a site | The URL you ask the app to fetch | That website |
| Agent, or an AI connection test | The text you submit, any project context that feature attaches, and the API key you saved | The API endpoint you configured (for example OpenAI, Gemini, or a server you run) |
| Chrome Web Store search or extension install | The search or extension request | Google (`chromewebstore.google.com`, `clients2.google.com`) |
| Userscript browse | The search request | GreasyFork (`api.greasyfork.org`) |
| Online background-music search | The search request | The music services used by that feature (`oiapi.net`, `music.163.com`, `itunes.apple.com`, `api.cenguigui.cn`) |
| Webpage auto-translation | The text being translated | The translation service you selected (MyMemory `api.mymemory.translated.net`, LibreTranslate `libretranslate.com`, or Lingva `lingva.ml`) |
| DNS-over-HTTPS, a proxy, or the TLS bridge | DNS lookups or proxied traffic | The resolver or proxy you selected |
| Ad-block list updates | The request for the list you enabled | The list's URL |
| Remote activation | The check your app is configured to make | The HTTPS endpoint you entered |
| Push notifications | Messages for a Firebase project | Firebase, and only after you supply that project's own configuration |
| Share or export | The file you chose | The app or folder you pick in the system share sheet |

Any network request also carries the usual connection data, such as an IP address. We do not use that data to build an advertising profile.

## Permissions

Sherwood WTA asks for a permission when a feature needs it, such as network access, notifications, or access to photos and files for icons and projects. Denying a permission turns that feature off. Permissions are not used to profile you for ads.

## Children

Sherwood WTA is a tool for building apps. It is not directed at children under 13.

## Changes

When this policy changes, the effective date at the top of this page changes with it. The current text on this page is the policy that applies.

## Contact

Questions about this policy: [github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues)
