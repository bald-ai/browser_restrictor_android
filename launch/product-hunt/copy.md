# Product Hunt launch

## Name
Browser Restrictor

## Tagline
Earn your scrolling. Change the rules by voice.

## Description
Do a few push-ups, hold a plank or go for a walk. Earn time in your browsers and X. Friction, the app behind Browser Restrictor, lets you tune activities, rewards and limits by voice or text. Tell it what to change, review it, approve it. A small Android experiment in using your phone on purpose.

## Maker comment
I made this for myself. I wanted opening a browser to be a choice again.

Do something useful, earn a little browsing time. Or leave the phone alone. Either outcome works for me.

The fun part is changing the rules. Tell the app what you want: different activities, shorter waits, more time per credit. Review the changes, then approve them. You can type too.

Astra is inside the app, handling those changes. It also one-shotted the voice UX and tested it end to end while I was building it. Unreal.

This is an early Android build. The app calls itself Friction. I'm sharing the experiment and would like to hear what rules you'd try.

## Astra Challenge
Astra is part of the product itself: it is the in-app agent that helps people change their activities, rewards and limits. Voice requests go to that same agent, with a review and approval before changes apply. During development, Astra genuinely one-shotted the voice UX for controlling the app and tested it end to end. That was unreal to watch. It made talking to the app a practical way to make it your own.

## Schedule to preserve
September 18, 2026, 12:01 AM PDT / 09:01 Europe/Prague.

## Source checks
- Current app guide and capability reference: supported apps, earned time, voice/text changes, approval.
- backend-v1/agent.mjs: gpt-6-astra configuration agent.
- backend-v1/voice.mjs: voice interface delegates to the backend.
- app/build.gradle.kts: Android 10+, version 0.1.0.
- Maker's supplied experience: Astra one-shotted the voice UX and tested it end to end.
- Captures in work/chat-ux-2026-09-17 are from today's app validation.
