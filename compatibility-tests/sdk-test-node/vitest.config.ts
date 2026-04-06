import { defineConfig } from 'vitest/config';

const resultsDir = process.env.TEST_RESULTS_DIR || 'test-results';

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    testTimeout: 60000,
    hookTimeout: 30000,
    include: ['tests/**/*.test.ts'],
    reporters: ['default', 'junit'],
    outputFile: {
      junit: `${resultsDir}/junit.xml`,
    },
    pool: 'forks',
    poolOptions: {
      forks: {
        singleFork: true,
      },
    },
  },
});
