# Technical architecture image prompts

Built-in image generation, 2026-09-16.

## Initial prompt

Create a PROFESSIONAL SOFTWARE ARCHITECTURE DIAGRAM, not an infographic or user journey. Landscape 16:10 or wider high-resolution. Flat white background, black/charcoal crisp large sans-serif text, thin orthogonal connector lines with arrowheads, rectangular components, lightly tinted deployment boundaries, small numbered interface labels. ABSOLUTELY NO illustrations, people, phones drawn as devices, robots, cartoon icons, clouds, gradients, 3D, slogans or decorative elements. Like a carefully composed C4 container / system component diagram in an engineering design document. Prioritize graph correctness and legibility. Title "Friction app — proposed runtime architecture". Subtitle "GPT-Live + Agents API | Technical proposal, not yet implemented".

THREE deployment boundaries across page, left to right:
LEFT 34% width pale slate border titled "ANDROID APP · Kotlin / Compose"
CENTRE 35% pale teal border titled "OUR BACKEND · existing Node service, expanded"
RIGHT 27% pale lavender border titled "OPENAI · managed services"

Android has top box "Conversation UI" with small subtitle "Voice · text · task/settings cards".
Middle box "Configuration repository" subtitle "Local copy of active setup".
Below box "Restriction + reward engine" subtitle "App detection · blocking · timers · credits".
Bottom box "Camera / GPS recorder" subtitle "Task ID · setup revision · recorded evidence".
Small storage box attached to restriction engine "Local state" subtitle "Balances · usage · pending attempts".

Backend top box "Agent gateway" subtitle "Conversation context · visible card · statistics".
Backend below-left box "App tool handlers" subtitle "get_setup · update_task · update_settings".
Backend below-right database plain rectangular box "Configuration store" subtitle "Tasks · rules · revisions".
Backend bottom box "Verification service" subtitle "Video preprocessing · GPS checks · task instructions".

OpenAI top box "GPT-Live" subtitle "Streaming audio · delegation".
OpenAI middle box "Agents API session" subtitle "Managed agent · instructions · tool requests".
OpenAI lower note in a thin outline separate from actual services: "Agent controls configuration through tools". No code execution sandbox drawn because that placement is unresolved.

Additional external rectangle outside all three boundaries bottom right titled "Video model provider" subtitle "Current verifier provider or replacement; selection open".

CONNECTIONS, exact meanings; draw neatly with right-angle routing and no lines passing through boxes:
1 Conversation UI ↔ GPT-Live, a long top routed BLUE connection across above backend box, label "1  Live audio · WebRTC (proposed)".
2 GPT-Live ↔ Agent gateway, BLUE connection label "2  Delegation + transcript context".
3 Agent gateway ↔ Agents API session, PURPLE connection label "3  Agent session messages / events".
4 Agents API session → App tool handlers, PURPLE connection label "4  Tool request". A paired opposite arrow or bidirectional connector indicates tool result back.
5 App tool handlers ↔ Configuration store, DARK connection label "Validate + save".
6 App tool handlers → Android Configuration repository, TEAL connection label "5  Configuration update · WSS". Return arrow label or bidirectional arrow with "Applied revision ACK".
7 Android Configuration repository → Restriction + reward engine and → Conversation UI, within Android boundary, label "Apply / render".
8 Android Camera / GPS recorder → backend Verification service, ORANGE connection label "6  Evidence upload · HTTPS".
9 Verification service ↔ Video model provider, ORANGE connection label "Video judging".
10 Verification service → Android Restriction + reward engine, ORANGE connection label "Verified measurement + receipt".
11 Local state → Agent gateway can be omitted to avoid clutter; gateway's context subtitle already shows statistics.

Tiny legend bottom: "Blue: voice | Purple: agent work | Teal: setup changes | Orange: verification"
Two short technical notes below diagram:
"Proposed ownership: server stores configuration; Android executes rules and keeps runtime state."
"App code calculates rewards. The conversational agent changes only supported configuration."
One small footer note: "Voice-to-agent bridge is ours to build; no direct GPT-Live → Agents API integration assumed."
Keep exact technical labels spelled correctly and minimal. The graph is the deliverable, not a metaphor. Route connections with sufficient spacing. If some internal details must be condensed for legibility, preserve deployment boundaries, numbered connections, both OpenAI services, tool handlers, configuration store, local state, verification service and restriction engine.

## Correction prompt

Correct this engineering architecture diagram. Preserve the three columns and all component boxes. Make the ENTIRE canvas fully opaque white, including title, footer, gaps, borders; eliminate all black/transparent background artifacts. Use plain crisp black title/subtitle/footer. Correct these five wiring errors: (1) DELETE the vertical purple bidirectional arrow between GPT-Live and Agents API session: there is NO direct connection. (2) DELETE the downward blue branch from the audio line to Agent gateway: the audio line connects Conversation UI and GPT-Live only. (3) DELETE the orange arrow from Camera/GPS recorder to Restriction+reward engine. Instead route a new orange arrow from the LEFT SIDE of Verification service back across the columns into Restriction+reward engine, label 'Verified measurement + receipt'. (4) Simplify the purple tool wiring to ONE clean bidirectional line between Agents API session and App tool handlers, labelled 'Tool requests / results'. Keep the separate gateway-to-Agent-session line. (5) Fix typo 'sonfiguration' to 'configuration'. Preserve other correct connectors, especially voice-to-gateway delegation, app-tool-handlers-to-configuration-store, backend-to-Android config update, camera-to-verifier upload and verifier-to-video-provider. Do not add other connectors. A clean accurate architecture document, absolutely no visual effects. Every label legible.
