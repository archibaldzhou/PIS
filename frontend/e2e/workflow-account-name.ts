/** Test-only account naming, mirrored by the disposable Java seed. */
export function workflowAccountName(file: string, prefix: string): string {
  const scenario = file.replaceAll('\\', '/').split('/').pop()?.match(/^([a-z]+)\.spec\.ts$/)?.[1];
  const username = `${prefix}.${scenario}`;
  if (!scenario || !prefix.trim() || username.length > 64) throw new Error('Invalid synthetic E2E account name');
  return username;
}
