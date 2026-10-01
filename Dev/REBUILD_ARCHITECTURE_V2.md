# Proposed runtime architecture

Superseded by [architecture v3](REBUILD_ARCHITECTURE_V3.md): the user chose function calling instead of the managed Agents API/coding-agent runtime. The v2 image is historical.

Technical proposal for discussion, not an implementation decision. The diagram below is the editable source of truth; the generated PNG is a visual companion. Its generated footer wording is not a confidentiality designation or additional architectural requirement.

```mermaid
flowchart LR
  subgraph android[Android app — Kotlin / Compose]
    ui[Conversation UI\nVoice, text, task/settings cards]
    config[Configuration repository\nLocal active setup]
    engine[Restriction and reward engine\nApp detection, blocking, timers, credits]
    recorder[Camera / GPS recorder\nTask ID and setup revision]
    local[(Local state\nBalances, usage, pending attempts)]
    config -->|Render| ui
    config -->|Apply rules| engine
    engine <--> local
    recorder -->|Persist pending attempt| local
  end

  subgraph backend[Our backend — existing Node service, expanded]
    gateway[Agent gateway\nContext, visible card, statistics]
    handlers[App tool handlers\nRead setup, update tasks/settings, preview]
    store[(Configuration store\nTasks, rules, revisions)]
    verifier[Verification service\nVideo preprocessing and GPS validation]
    gateway <--> handlers
    handlers <-->|Validate and save| store
    store -->|Approved task definition| verifier
  end

  subgraph openai[OpenAI managed services]
    live[GPT-Live\nStreaming audio and delegation]
    agent[Agents API session\nManaged agent and app tools]
  end

  provider[Video model provider\nExisting provider or replacement]
  ui <-->|Audio: WebRTC proposed| live
  live <-->|Delegation events, transcript context, results| gateway
  gateway <-->|Session messages and events| agent
  agent <-->|Tool requests and results via our integration| handlers
  ui <-->|Text, visible context, cards: WSS proposed| gateway
  local -->|Statistics when requested| gateway
  handlers -->|Configuration revision: WSS proposed| config
  config -->|Applied revision acknowledgement| handlers
  recorder -->|Evidence: HTTPS| verifier
  verifier <-->|Video judging| provider
  verifier -->|Verified measurement and receipt| engine
```

## Proposed ownership

- The backend is authoritative for configuration; Android keeps an active local copy.
- Android executes blocking and reward rules and owns durable runtime state in this proposal, retaining existing foundations. Requiring a connection is a product rule, not a requirement to run every timer remotely.
- Our integration connects GPT-Live to the Agents API. No native direct connection between those APIs is assumed.
- Tool handlers validate changes; the agent cannot edit app source code or choose arbitrary capabilities.
- The verification service measures activity. The Android reward engine calculates credits from verified measurements.
- API transport feasibility, identity/authentication, server storage choice, reconnect behavior, conflict handling, and billing still require technical validation.
- Several boxes can run in the same backend deployment. They describe responsibilities, not a requirement for separate servers.

## Generated image

[Architecture image v2](REBUILD_ARCHITECTURE_V2.png)

Generated using the built-in image generation tool. The exact prompts are recorded in [REBUILD_ARCHITECTURE_V2_PROMPT.md](REBUILD_ARCHITECTURE_V2_PROMPT.md).
