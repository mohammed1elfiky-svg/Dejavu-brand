/** @type {import('jest').Config} */
const base = {
  moduleFileExtensions: ['js', 'json', 'ts'],
  transform: { '^.+\\.ts$': 'ts-jest' },
  testEnvironment: 'node',
};

module.exports = {
  projects: [
    { ...base, displayName: 'unit', rootDir: 'src', testRegex: '.*\\.spec\\.ts$' },
    {
      ...base,
      displayName: 'e2e',
      rootDir: 'test',
      testRegex: '.*\\.e2e-spec\\.ts$',
      globalSetup: '<rootDir>/global-setup.ts',
      setupFiles: ['<rootDir>/env.ts'],
    },
  ],
};
