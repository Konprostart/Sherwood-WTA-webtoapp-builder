# Agent

A tool-calling assistant inside the app that can operate the entire Sherwood WTA surface. Open it from [⋮ → Agent](/guide/main-screen/more).

## What it can do

Beyond generating web apps, plugins, userscripts, MV3 Chrome extensions, and local runtime projects, the Agent can directly perform actions across the app:

- **App lifecycle** — create, edit, duplicate, delete, build APK/AAB, export, share, create shortcuts, move to categories.
- **Ports & engines** — scan/kill ports, check/select/delete browser engines (WebView, GeckoView).
- **Runtimes** — check status, install, and clear caches for Node.js, PHP, Python, Go, WordPress, and the Linux environment.
- **Ad-block** — view rule counts and sources, import/remove/enable/disable hosts subscriptions.
- **Stats & health** — usage statistics, URL health checks.
- **App modifier** — list installed apps, clone/rebrand apps, batch import from text, export templates.
- **Build environment & compliance** — initialize the Linux build environment, install components, run Google Play policy checks.
- **Modules** — list, create, and update plugins.
- **Files** — read, write, edit, delete, list, glob, and grep project files.
- **GitHub** — check the Studio connection, list repositories, create a **new independent repository** (optionally seeded with a copy of a public repository's files, which is the rebrand path), and copy a repository into the chat's working folder. `GitHubOpenPullRequest` sends the edited files back as a **new branch plus a pull request**: it never commits to your base branch, never force-pushes and never merges, and it asks for a fresh approval tap on every push. `GitHubCreateRepository` never forks and also asks for a fresh tap on every call.

## Features

- **Sessions** — each conversation has its own title and history.
- **Up to 65 built-in tools** — grouped by domain (files, apps, config templates, lifecycle, ports/engine, hosts/runtime, stats/modifier/import, build env/Play, modules, GitHub, imagery, plan mode). That is 60 base tools, 2 plan-mode tools, and 3 imagery tools. The imagery tools only load with an image-capable model. Read-only tools run without confirmation; write tools ask for permission first. `ExportConfigTemplate` is read-only; `ImportConfigTemplate` asks first. `GitHubOpenPullRequest` and `GitHubCreateRepository` never offer "Always allow" and auto-approve does not skip them — every push and every repository creation is approved on its own.
- **Plan mode** — proposes a plan and waits for your approval before applying changes (shown with a plan-mode badge).
- **Resilience** — automatic retry with backoff on 429/5xx responses.

## Local MCP

[About → Local MCP](/guide/more-features/about) is off by default. When it is on, an external agent can call the same tools over `127.0.0.1`, with a bearer token. A read-only preview tool opens the page in a document task you can watch. Write tools still ask on the phone. Generated APKs do not ship this server. A computer reaches the port with `adb reverse`.

## Floating assistant (Sidekick)

A small consultation panel that floats above every screen, so you can ask about the work without leaving it. Turn it on in **Settings → Floating assistant**; a draggable button then appears above the app and remembers where you left it.

- **What it is for** — explaining what the Agent just did, explaining an error or build failure in plain language, and rewriting a prompt into something the Agent can act on.
- **It cannot act.** The Sidekick has no tools: it cannot read project files, change settings, build, or push. It answers, and tells you where in the app to do the thing.
- **Context** — it can attach the tail of the current Agent session (a size-bounded digest, sent as system context, never as your own message) so "explain this error" works without copy-paste. A short "this installation" block answers questions like which git host is active without guessing.
- **Its own model** — pick any saved model for it, including a [local Ollama model](/guide/more-features/ai-settings), or leave it on "Same as the agent" to follow the Agent's model. A model chosen here never changes the Agent's model, and a pick that was deleted from AI Settings falls back to the Agent's instead of failing.
- **Images and files** — attach a screenshot, a photo taken with the camera, or a text file (logs, JSON, source, config). Images travel as vision input, so the model must be a multimodal one; otherwise the panel says so and the image buttons stay off. Text files are pasted into the question inside an explicit `UNTRUSTED_FILE_DATA` fence, bounded to 2 MB per file and 20,000 characters per question — a larger file is refused with a reason, never silently truncated.
- **Images expire from context** — only the two newest questions keep their images, because a full-resolution screenshot costs a few thousand tokens and a long conversation would otherwise overflow the model's window. Older turns keep a short `[image omitted]` marker so the transcript stays honest.
- **Dictation** — the microphone button turns speech into text. Two engines are available (see below); the default is the phone's own speech service: no extra download, no API key. Pick **Automatic**, Serbian, English or Swedish under **Settings → Floating assistant → Dictation language**. "Prefer on-device recognition" asks the platform to keep the audio local where it can; devices without an on-device recognizer, or without the chosen language, report that instead of guessing. A device with no recognition service at all says so and the button stays disabled.
- **Whisper server engine** — for languages the phone may not offer, or to keep the audio on your own network, set **Settings → Floating assistant → Dictation engine** to *Whisper server* and fill in the server. Both request shapes are supported: OpenAI-compatible (`POST {base}/audio/transcriptions`, e.g. OpenAI, Groq, speaches, faster-whisper-server) and whisper.cpp (`POST {base}/inference`). A take is recorded as a 16 kHz mono WAV, capped at two minutes (3.8 MB), streamed from disk to the server, and deleted afterwards. **Test connection** posts one second of silence to the real endpoint, so a wrong path, protocol or key is reported before you dictate.
- **Server URL rules** — HTTPS is required for any server on the internet. `http://` is accepted only for loopback or a private LAN address and only when no API key is stored: a key sent over cleartext would be readable on the network. The key is stored through the same AES-GCM keystore encryption as the git tokens, never logged, and redirects are not followed (so the key cannot be replayed at another host). The audio goes to whichever server you configure — a LAN server keeps it on your network, a hosted API does not.
- **Host only** — the assistant, its attachments and its dictation are part of the builder app. Generated APKs do not contain any of it, and no new runtime permission reaches them.

## Configuration

Agent uses the model and keys configured in [AI Settings](/guide/more-features/ai-settings).

## Notes

Agent produces *source* and performs *actions*. To install a generated extension, save it through the [Manage Plugins](/guide/more-features/extension-modules) flow.
