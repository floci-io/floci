import {
  ListVersionsByFunctionCommand,
  PublishVersionCommand,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

export function useVersions(name: string) {
  return useQuery({
    queryKey: lambdaKeys.versions(name),
    queryFn: async () => {
      const all = [];
      let marker: string | undefined;
      do {
        const r = await lambda.send(
          new ListVersionsByFunctionCommand({
            FunctionName: name,
            Marker: marker,
          }),
        );
        all.push(...(r.Versions ?? []));
        marker = r.NextMarker;
      } while (marker);
      return all;
    },
    enabled: !!name,
  });
}

export function usePublishVersion(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (description?: string) => {
      await lambda.send(
        new PublishVersionCommand({
          FunctionName: name,
          Description: description,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.versions(name) }),
  });
}
