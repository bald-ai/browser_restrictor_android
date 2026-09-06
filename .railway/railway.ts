import { defineRailway, preserve, project, service } from "railway/iac";

export default defineRailway(() => {
  const exerciseVerifier = service("exercise-verifier", {
    replicas: { "europe-west4-drams3a": 1 },
    healthcheck: "/health",
    healthcheckTimeout: 60,
    deploy: {
      restartPolicyType: "ON_FAILURE",
      restartPolicyMaxRetries: 3,
    },
    env: {
      PORT: "8080",
      OPENROUTER_API_KEY: preserve(),
      VERIFY_SHARED_SECRET: preserve(),
    },
  });

  return project("browser-restrictor", {
    resources: [exerciseVerifier],
  });
});
