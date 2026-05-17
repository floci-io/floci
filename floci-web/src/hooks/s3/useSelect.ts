import {
  SelectObjectContentCommand,
  type CSVInput,
  type CSVOutput,
  type JSONInput,
  type JSONOutput,
} from '@aws-sdk/client-s3';
import { useMutation } from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';

export type SelectInputFormat = 'CSV' | 'JSON' | 'Parquet';
export type SelectOutputFormat = 'CSV' | 'JSON';

export interface SelectInput {
  bucket: string;
  key: string;
  expression: string;
  inputFormat: SelectInputFormat;
  outputFormat: SelectOutputFormat;
  csvInput?: CSVInput;
  jsonInput?: JSONInput;
  csvOutput?: CSVOutput;
  jsonOutput?: JSONOutput;
}

export interface SelectResult {
  text: string;
  bytesScanned?: number;
  bytesReturned?: number;
}

export function useSelect() {
  return useMutation({
    mutationFn: async (input: SelectInput): Promise<SelectResult> => {
      const inputSer =
        input.inputFormat === 'CSV'
          ? { CSV: input.csvInput ?? { FileHeaderInfo: 'USE' } }
          : input.inputFormat === 'JSON'
            ? { JSON: input.jsonInput ?? { Type: 'LINES' } }
            : { Parquet: {} };

      const outputSer =
        input.outputFormat === 'CSV'
          ? { CSV: input.csvOutput ?? {} }
          : { JSON: input.jsonOutput ?? {} };

      const r = await s3.send(
        new SelectObjectContentCommand({
          Bucket: input.bucket,
          Key: input.key,
          ExpressionType: 'SQL',
          Expression: input.expression,
          InputSerialization: inputSer,
          OutputSerialization: outputSer,
        }),
      );

      const chunks: Uint8Array[] = [];
      let bytesScanned: number | undefined;
      let bytesReturned: number | undefined;

      if (r.Payload) {
        for await (const ev of r.Payload) {
          if (ev.Records?.Payload) {
            chunks.push(ev.Records.Payload);
          }
          if (ev.Stats?.Details) {
            bytesScanned = ev.Stats.Details.BytesScanned;
            bytesReturned = ev.Stats.Details.BytesReturned;
          }
        }
      }

      const total = chunks.reduce((n, c) => n + c.length, 0);
      const merged = new Uint8Array(total);
      let off = 0;
      for (const c of chunks) {
        merged.set(c, off);
        off += c.length;
      }
      const text = new TextDecoder().decode(merged);
      return { text, bytesScanned, bytesReturned };
    },
  });
}
