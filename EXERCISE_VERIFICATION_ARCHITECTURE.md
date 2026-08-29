# Exercise verification architecture

The launchable challenge is a picker-less generic recording. The verifier classifies
the clip, and the Android client converts the raw measurement into shared exercise
sets.

## Exercise readiness

| Exercise ID | Measurement | Recording limit | Units per set | Current state |
| --- | --- | ---: | ---: | --- |
| `push_up` | repetitions | 30 seconds | 5 repetitions | enabled |
| `plank` | continuous hold seconds | 30 seconds | 18 seconds | enabled |
| `outdoor_lat_pulldown` | repetitions | 30 seconds | 10 repetitions | enabled |
| `unsupported` | none | 30 seconds | none | fail-closed classification result |
| `sit_up` | repetitions | 30 seconds | TBD | waiting; classified as unsupported |

Credit is computed only on the client. 5 verified push-ups, 18 valid plank seconds, or
10 valid machine repetitions complete one set. Each set adds 3 spendable browser
minutes. Any combination of 4 sets unlocks YouTube until 04:00. Surplus is stored
independently per exercise and survives the 20:00 spendable-budget reset. The first
completed set of the logical day enables the emergency unlock. Monthly 9th-to-9th
history remains verified push-ups only.

## Verification contract

New clients use `POST /v1/exercise-verify` with one multipart field:

- `video`: one `video/mp4` file up to 75 MB and 30 seconds.

The endpoint does not take an exercise picker. It classifies among `push_up`,
`plank`, `outdoor_lat_pulldown`, and `unsupported`. The successful response is a raw
measurement:

```json
{
  "exercise_id": "plank",
  "metric": "hold_seconds",
  "value": 20,
  "reason": "..."
}
```

Business thresholds stay outside the model prompt. Domain code converts `value` into
completed sets and remainder. `POST /v1/pushup-verify` and its existing
`{is_success, confidence, reason, repetition_count}` response remain supported for the
currently installed push-up APK.

The generic contract deliberately has no confidence field. The classifier must either
return one supported exercise with its raw measurement or return `unsupported`; the
client does not carry an unused confidence signal.

The preprocessor still samples at `MODEL_SAMPLE_FPS=4` using `fps=4,setpts=N/1/TB`.
For planks, the model reports the visually measured densified playback seconds and the
server divides by four and rounds to the nearest original second. Keeping this simple
arithmetic out of the model avoids inconsistent duration conversion. Repetition counts
are not scaled.

## Android boundary

`ExerciseChallengeCatalog` remains the client-facing list of stable IDs, metrics,
recording limits, and set sizes. The camera Activity has no picker: its primary CTA is
Start exercise, and every upload goes to `/v1/exercise-verify`. Sit-ups and other
unrecognized clips fail closed without writing credit.

Schema v10 stores typed per-exercise remainder plus a dedicated verified-push-up
counter so plank and machine sets cannot inflate push-up history. Every recording gets
a client-generated verification ID. Crediting the measurement and storing that ID are
one atomic state change, so replaying the same result cannot award credit twice. The
IDs reset with the rest of the logical-day state at 04:00.
