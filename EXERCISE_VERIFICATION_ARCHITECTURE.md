# Exercise verification architecture

The current push-up flow remains the only launchable exercise. The code now has
stable exercise IDs and distinct measurement types so future activities do not
masquerade as push-up repetitions.

## Exercise readiness

| Exercise ID | Measurement | Recording limit | Qualifying value | Current state |
| --- | --- | ---: | ---: | --- |
| `push_up` | repetitions | 30 seconds | 5 repetitions | enabled |
| `plank` | continuous hold seconds | 30 seconds | 25 seconds | waiting for example-derived prompt and reward policy |
| `outdoor_lat_pulldown` | repetitions | 30 seconds | TBD | waiting for example-derived prompt, qualifying count, and reward policy |
| `sit_up` | repetitions | 30 seconds | TBD | waiting for example-derived prompt, qualifying count, and reward policy |

An unfinished exercise must remain unavailable even if someone constructs its
Activity intent or calls the verifier endpoint directly.

## Verification contract

New integrations use `POST /v1/exercise-verify` with two multipart fields:

- `exercise_id`: one of the stable IDs above.
- `video`: one `video/mp4` file using the existing size and 30-second limits.

The successful response is metric-neutral:

```json
{
  "exercise_id": "plank",
  "metric": "hold_seconds",
  "value": 25,
  "is_success": true,
  "confidence": 0.9,
  "reason": "..."
}
```

The server selects the prompt, strict model schema, result field, and qualifying
threshold from its exercise definition. Business thresholds stay outside the
model prompt. `POST /v1/pushup-verify` and its existing response remain supported
for the installed push-up client.

## Android boundary

`ExerciseChallengeCatalog` mirrors only the client-facing contract: stable ID,
metric, recording limit, qualifying value, and readiness. The existing camera
Activity defaults to push-ups and includes `exercise_id` in every upload. A
non-push-up Activity request is rejected until both verification and its reward
policy are ready.

The current push-up reward, daily remainder, YouTube threshold, reports, and
9th-to-9th ledger are deliberately unchanged. A new exercise must not write its
seconds or repetitions into those push-up fields.

## Work after example videos arrive

For each recording:

1. Identify only cues visible from the repeatable real-world phone position.
2. Write the prompt and strict schema field for that measurement.
3. Benchmark representative positive, partial, setup, and failed attempts.
4. Decide the qualifying repetition count where still TBD.
5. Decide how the activity earns access and whether it needs its own history.
6. Add the reward/storage handler and tests, then mark both server and Android
   definitions ready.

