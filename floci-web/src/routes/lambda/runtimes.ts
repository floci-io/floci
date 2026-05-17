export const LAMBDA_RUNTIMES = [
  'nodejs24.x',
  'nodejs22.x',
  'nodejs20.x',
  'nodejs18.x',
  'python3.13',
  'python3.12',
  'python3.11',
  'python3.10',
  'java21',
  'java17',
  'java11',
  'go1.x',
  'ruby3.3',
  'ruby3.2',
  'dotnet8',
  'provided.al2023',
  'provided.al2',
] as const;

export const LAMBDA_ARCHITECTURES = ['x86_64', 'arm64'] as const;

export async function fileToUint8Array(file: File): Promise<Uint8Array> {
  const buf = await file.arrayBuffer();
  return new Uint8Array(buf);
}
