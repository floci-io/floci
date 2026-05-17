import {
  InvocationType,
  InvokeCommand,
  LogType,
} from '@aws-sdk/client-lambda';
import { useMutation } from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';

export interface InvokeInput {
  functionName: string;
  payload: string;
  invocationType: InvocationType;
  logType: LogType;
  qualifier?: string;
}

export interface InvokeResult {
  statusCode?: number;
  functionError?: string;
  executedVersion?: string;
  payload: string;
  logTail: string;
}

function base64Decode(b64: string): string {
  try {
    return atob(b64);
  } catch {
    return b64;
  }
}

export function useInvoke() {
  return useMutation({
    mutationFn: async (input: InvokeInput): Promise<InvokeResult> => {
      const encoder = new TextEncoder();
      const r = await lambda.send(
        new InvokeCommand({
          FunctionName: input.functionName,
          Payload:
            input.payload && input.payload.length > 0
              ? encoder.encode(input.payload)
              : undefined,
          InvocationType: input.invocationType,
          LogType: input.logType,
          Qualifier: input.qualifier,
        }),
      );

      const payloadText = r.Payload
        ? new TextDecoder().decode(r.Payload as Uint8Array)
        : '';
      const logTail = r.LogResult ? base64Decode(r.LogResult) : '';

      return {
        statusCode: r.StatusCode,
        functionError: r.FunctionError,
        executedVersion: r.ExecutedVersion,
        payload: payloadText,
        logTail,
      };
    },
  });
}
