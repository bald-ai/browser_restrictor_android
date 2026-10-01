# Teach by demonstration

Status: unimplemented design proposal. The following ideas and API sketches are
not part of the current app or deployed verifier.

## Idea

Allow the owner to add a new physical task by recording a video of themselves doing
it correctly. The app sends that demonstration to the model, which creates a reusable,
structured definition of the task. Future challenge recordings are checked against
that definition without the owner having to write a task-specific model prompt.

The experience should feel like:

1. Tap **Teach a new task**.
2. Record one clear demonstration.
3. Review what the model understood.
4. Choose how much work completes one set.
5. Save the task.
6. The task becomes available to the generic exercise verifier in future recordings.

This is a natural extension of the current picker-less exercise challenge. Built-in
exercises and owner-taught tasks can ultimately be recognized through the same camera
flow and can award the same shared-pool minutes.

## What “learn” means

The first version should not claim to retrain or permanently fine-tune the foundation
model from one video. Instead, the model observes the demonstration once and produces
a durable, machine-readable `TaskDefinition`. That definition is stored by the app and
included in later verification requests.

The model therefore learns the task at the product level:

- the owner teaches by video rather than writing a prompt;
- the app remembers the generated definition;
- the generic verifier uses that definition for future recordings;
- the base model itself is not modified.

Keeping this distinction explicit gives us a practical first implementation and leaves
fine-tuning or an on-device motion model as possible later improvements.

## Proposed task definition

The teaching endpoint should return structured data rather than free-form prompt text.
A possible contract is:

```json
{
  "schema_version": 1,
  "suggested_name": "Chair squats",
  "metric": "repetitions",
  "observed_units": 6,
  "start_state": "Standing upright in front of a chair",
  "completion_state": "Hips touch the chair, then the person returns to standing",
  "required_motion": [
    "hips move down toward the chair",
    "hips visibly contact or closely approach the seat",
    "knees and hips extend back to the standing position"
  ],
  "do_not_count": [
    "partial descents",
    "a descent without returning to standing",
    "repetitions where the relevant body and chair are not visible"
  ],
  "visibility_requirements": [
    "full torso, hips, knees, and chair remain visible"
  ],
  "ambiguities": [],
  "model_summary": "Count one repetition for each complete stand-to-chair-to-stand cycle."
}
```

Supported metrics for the first iteration should match concepts the existing app can
credit reliably:

| Metric | Meaning | Example |
| --- | --- | --- |
| `repetitions` | Count complete motion cycles | squats, curls, step-ups |
| `hold_seconds` | Measure a continuously maintained state | wall sit, balance pose |
| `completions` | Recognize a single completed sequence | short mobility routine |

The owner chooses `units_per_set` after the draft is generated. Business rules such as
minutes awarded per set remain client-side, just as they do for built-in exercises.
The teaching model describes and measures the movement; it does not decide the reward.

## End-to-end flow

```text
Teach mode
  -> record demonstration
  -> preprocess video using the existing frame pipeline
  -> model generates a structured TaskDefinition
  -> owner reviews name, metric, and units per set
  -> app saves the accepted definition

Future challenge
  -> record an attempt
  -> send built-in and custom definitions with sampled video
  -> model classifies the visible task
  -> model returns task ID, metric, value, and reason
  -> existing domain logic converts value into sets and shared-pool minutes
```

The review step is important even though the owner does not write a prompt. A single
video can be ambiguous, and the model may infer the wrong boundary between one
repetition and the next. Review lets the owner correct the name, metric, or set size
without exposing prompt engineering.

## API sketch

### Create a draft from a demonstration

`POST /v1/task-teach`

Multipart fields:

- `video`: the demonstration MP4;
- `task_id`: a client-generated stable ID used for idempotency.

Response:

```json
{
  "task_id": "custom_01J...",
  "definition": {
    "schema_version": 1,
    "suggested_name": "Chair squats",
    "metric": "repetitions",
    "observed_units": 6,
    "start_state": "...",
    "completion_state": "...",
    "required_motion": ["..."],
    "do_not_count": ["..."],
    "visibility_requirements": ["..."],
    "ambiguities": [],
    "model_summary": "..."
  }
}
```

### Verify an attempt

The existing generic endpoint could be extended, or a new
`POST /v1/task-verify` endpoint could accept:

- the attempt video;
- the IDs and definitions of enabled custom tasks;
- the built-in task catalog version.

It should return the same raw-measurement shape used today:

```json
{
  "task_id": "custom_01J...",
  "metric": "repetitions",
  "value": 5,
  "reason": "Five complete chair-touch squat cycles were visible."
}
```

The server must validate the definition schema and bound all string and list sizes
before placing custom content in a model request.

## Storage approach

For the first version, store the accepted structured definition on the Android device
and continue deleting uploaded videos after processing. This preserves the current
temporary-video model and avoids creating a permanent server-side video library.

Suggested local state:

```text
CustomTask
  id
  name
  metric
  unitsPerSet
  definition
  createdAtWallMs
  enabled
  definitionVersion
```

Per-task remainder should be stored in a map keyed by the stable custom task ID. The
current fixed remainder fields for push-ups, plank, and the machine exercise will need
a schema migration before custom tasks can earn partial progress safely.

If text definitions alone prove insufficient, a later version can optionally retain an
owner-approved reference clip or derived visual representation. That should be a
separate product choice rather than an invisible change to the current deletion rule.

## Recognition strategy

### MVP: generated specification

Generate the structured definition from one demonstration and use it in subsequent
multimodal verification prompts. This is the fastest route and requires no model
training infrastructure.

### Stronger version: positive and negative examples

Let the owner record:

- two correct demonstrations from slightly different angles;
- optionally, one example that should not count.

The model can use these to refine the definition and identify ambiguity. This will be
more reliable for movements that resemble one another.

### Later: motion embeddings or fine-tuning

If usage grows enough to justify it, extract pose or video embeddings and train a small
task-specific classifier. This could reduce repeated prompt size and make recognition
more deterministic, but it is not required to validate the product idea.

## Product boundaries for the first experiment

Start with short, visually measurable physical actions that fit within the existing
30-second recording flow. Good candidates have a visible start state, completion state,
and count or hold duration.

Defer tasks that require:

- reading text or inspecting a phone/computer screen;
- proving an outcome that is not visible in the clip;
- identifying exact weight, distance, resistance, or force;
- long routines with many ordered stages;
- safety-critical form assessment;
- recognizing other people without their participation.

The broader “record anything and teach it” vision can remain the direction, but a
physical-action MVP fits the app’s current reward model and makes success measurable.

## Failure behavior

Custom verification should fail closed, like unsupported built-in exercises. Award no
credit when:

- the custom definition is missing or malformed;
- the relevant body parts or equipment are not visible;
- the candidate matches multiple enabled tasks equally well;
- the model cannot identify complete units;
- the returned metric differs from the saved definition;
- a verification receipt has already been credited.

The response should explain what was missing so the owner can re-record from a better
angle. A failed match must not silently fall back to the closest-looking custom task.

## Implementation impact

### Android

- Add a **Teach a new task** flow in Settings.
- Reuse the existing camera and upload pipeline in a distinct teaching mode.
- Add draft review, naming, metric confirmation, and units-per-set controls.
- Persist versioned custom definitions.
- Generalize exercise remainder storage from fixed fields to task-ID keyed entries.
- Include enabled custom definitions in generic verification requests.
- Show custom task progress in the challenge result and activity history.

### Server

- Add a schema-constrained teaching endpoint.
- Use a fixed system prompt that turns a demonstration into `TaskDefinition` JSON.
- Validate and normalize all generated fields.
- Extend verification to compare attempts against enabled custom definitions.
- Preserve one-use verification receipts and temporary-file deletion.
- Add request-size limits for the number and total size of custom definitions.

### Model evaluation

Build a small private evaluation set for each taught task:

- clear positive attempts;
- incomplete attempts;
- visually similar but different movements;
- bad camera angles and partial visibility;
- zero-repetition clips;
- transitions between two protected tasks.

Track classification accuracy, counting error, false-credit rate, and unsupported rate.
False credit is the most important failure because it directly creates spendable time.

## MVP acceptance criteria

The first experiment is successful when:

1. The owner can create a custom repetitive task without writing model instructions.
2. The generated definition is shown and can be accepted or discarded.
3. The task survives app restart and can be disabled or deleted.
4. A future recording can be classified against that definition and return a raw count.
5. The client awards sets using the configured units-per-set value.
6. Custom sets add to the same browser-and-X pool and YouTube set threshold.
7. Unsupported, ambiguous, and duplicate recordings award no credit.
8. Demonstration and attempt videos are deleted after processing.

## Open questions

- Is one positive demonstration sufficient, or should two be required before saving?
- Should custom tasks join the picker-less classifier immediately, or should the owner
  select a custom task during the initial experiment?
- May the owner edit model-generated recognition criteria, or only name and set size?
- How many enabled definitions can be sent before classification quality degrades?
- Should custom tasks count toward the monthly history, or should that remain strictly
  verified push-ups?
- Should a task definition be exportable so it can be backed up or shared later?
- If a saved definition is refined, how should old remainder and report history be
  associated with the new version?

## Recommended first prototype

Implement a narrow vertical slice with one owner-taught repetitive exercise:

1. Record one demonstration.
2. Generate and display the structured definition.
3. Let the owner confirm the name and repetitions per set.
4. Save it locally.
5. Require explicit selection of that custom task for the first verification prototype.
6. Compare several new attempts with the saved definition.
7. Only after evaluation, add custom tasks to the picker-less automatic classifier.

Explicit selection in the prototype isolates whether demonstration-derived definitions
work. Automatic classification among all built-in and custom tasks can then be tested
as a separate problem rather than mixing both uncertainties at once.
