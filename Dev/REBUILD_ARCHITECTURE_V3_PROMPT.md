# Architecture v3 image prompts

Generated using the built-in image generation tool.

## Initial prompt

Create a precise PROFESSIONAL SOFTWARE ARCHITECTURE DIAGRAM. Fully opaque WHITE background everywhere. Landscape wide high resolution, clean black typography, thin orthogonal arrows, rectangular components, no icons, no illustrations, no gradients, no robots, no decorative effects. Title: "Friction app — function-calling architecture". Subtitle: "Proposed implementation • No coding-agent runtime".

Use THREE clearly bordered vertical deployment columns:
LEFT light blue "ANDROID APP"
CENTRE light teal "OUR BACKEND"
RIGHT light lavender "OPENAI"

Android boxes top to bottom:
"Conversation UI" / "Voice · text · task/settings cards"
"Active configuration" / "Local copy of tasks and rules"
"Restriction + reward engine" / "Blocking · timers · credit calculation"
"Local state" / "Usage · balances · pending recordings"
"Camera / GPS recorder"

Backend boxes top to bottom:
"Conversation controller" / "Current setup · visible card · statistics"
"App functions" / "get_setup · update_task · update_settings"
"Configuration store" / "Tasks · rules · revisions"
"Verification service" / "Video preprocessing · GPS checks"

OpenAI boxes:
"GPT-Live" / "Voice conversation"
"Model with function calling" / "Requests supported app actions"
Outside OpenAI boundary at bottom right separate outlined box:
"Video model provider" / "Existing provider or replacement"

Draw exactly these principal connections cleanly with arrow labels:
Conversation UI <-> GPT-Live: "Audio"
GPT-Live <-> Conversation controller: "Delegation / results"
Conversation UI <-> Conversation controller: "Text / UI context / cards"
Conversation controller <-> Model with function calling: "Context / function calls / results"
Conversation controller <-> App functions: "Execute / return result"
App functions <-> Configuration store: "Validate / save"
App functions <-> Active configuration: "Update / applied acknowledgement"
Active configuration -> Conversation UI: "Render"
Active configuration -> Restriction + reward engine: "Apply rules"
Restriction + reward engine <-> Local state, no label
Local state -> Conversation controller: "Statistics"
Camera / GPS recorder -> Verification service: "Evidence upload"
Configuration store -> Verification service: "Task definition"
Verification service <-> Video model provider: "Video judging"
Verification service -> Restriction + reward engine: "Measurement + receipt"

Important: NO Agents API box, NO coding agent, NO sandbox, NO shell, NO repository. No direct line GPT-Live to function-calling model: our controller bridges them in this proposal. Only our app functions change configuration. Only app code calculates credits. Keep graph layout spacious, route connectors around boxes; crossing lines must not look like junctions. Main conversation and configuration flow in top two-thirds; recording and verification flow at bottom.
Footer exactly: "Model chooses a function. Our backend executes it. Android applies the result."
Small second footer: "Proposed ownership: server configuration; Android runtime state. Exact transport and model choices remain open."
All text crisp and readable, no added notes or invented labels.

## Edit prompt

Precisely edit this architecture diagram. Preserve everything except these TWO missing arrow details. 1) Add a clearly visible bidirectional connection labelled 'Audio' from Conversation UI to GPT-Live. Route it above the three deployment boundaries, in the whitespace beneath the subtitle, entering Conversation UI and GPT-Live from their top edges. It must NOT connect to Our Backend or intersect boxes. You may increase vertical whitespace between the subtitle and columns slightly to fit this route. 2) On the existing line between App functions and Active configuration, add an arrowhead at the App functions end too so the line is bidirectional, correctly representing the applied acknowledgement. Preserve all existing labels and connectors, no other edits, fully opaque white background, crisp typography.
