Generated using the built-in image-generation tool, with the user's original diagram as the edit target.

Edit the supplied architecture diagram into a new, technically accurate version. Redraw completely for clarity; preserve professional white background, crisp black typography, pale blue/teal/lavender grouping. Landscape high resolution, generous space, readable labels. No decorative art. Title: "Friction app — voice + app functions". Subtitle: "Working prototype + proposed Android integration • v2". This is a precise system diagram. Use TWO horizontal sections, visually distinct. Upper section 70%: "WORKING PROTOTYPE — browser + local backend". Lower section 25%: "ANDROID INTEGRATION — proposed; not connected to this prototype". Avoid implying lower section is implemented.

UPPER SECTION: Three columns CLIENT (blue), OUR BACKEND (teal), OPENAI (lavender).
Client box: "Conversation UI" then "Voice • text • review cards" and "Confirm • Cancel • Undo".
OpenAI upper box: "GPT-Live-1" then "Ripple voice • interruptions".
A prominent blue bidirectional arrow routed ABOVE backend, with endpoints exclusively client conversation UI and GPT-Live-1, label "Direct WebRTC audio". It must visibly bypass backend. No audio arrow to Astra.
Backend top box: "Conversation controller" then "Context • clarification • session control".
Client ↔ controller arrow label "Text / cards / approval / status".
Controller ↔ GPT-Live box arrow label "Sideband WebSocket" second line "Delegation / results".
OpenAI second box: "GPT-6 Astra" then "Low reasoning • Fast" then "Responses API • function calling".
Controller ↔ Astra arrow label "Context / tool calls / results".
Backend second box below controller: "Fixed app functions" then "Read docs + setup • propose changes" then "Confirm • cancel / undo • verify".
Controller ↔ fixed functions vertical arrow label "Execute / return result".
Backend third box below functions: "Saved app configuration" then "Tasks • settings • revisions" then "Drafts • previous values • change receipts".
Functions ↔ store arrow label "Validate / save / read back".
A small nearby "Product docs + sample statistics" box connected to functions with read arrow. Keep clearly out of audio path.
Below upper diagram show a concise horizontal process strip with exact text: "Read → Clarify if needed → Review card → User approval → Save → Read back → Verified".
Below that one small line: "Cancel: discard a draft • block a pending save or undo it afterward • restore and verify saved values".
A separate small note: "Backend executes supported functions. No coding-agent runtime."

LOWER SECTION, visually separated dashed boundary, three horizontally aligned groups with dashed arrows:
Left blue "Android app" box: "Conversation UI • local configuration" / "Blocking, timers and local state" / "Camera / GPS recording".
Middle teal "Backend integration" box: "Configuration sync + device acknowledgement" / "Video / GPS verification" / "Activity result + receipt".
Right gray "Video model provider" box: "Provider to be selected".
Dashed two-way Android ↔ Backend link label "Config / stats / evidence / results".
Dashed two-way Backend ↔ Video provider link label "Video judging".
At bottom exact note: "Android sync, verifier integration and final balance ownership still need implementation decisions."
Do not depict Astra directly saving config or granting rewards. Do not confuse verify saved configuration with video/GPS activity verification. Keep all arrows legible and attached to correct boxes, no overlapping text. Lower section is deliberately a high-level planned boundary, not settled detailed ownership.


Final cleanup: remove unintended parenthetical layout-percentage labels from the two section headings; preserve all other content.
