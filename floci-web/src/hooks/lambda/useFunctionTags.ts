import {
  ListTagsCommand,
  TagResourceCommand,
  UntagResourceCommand,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

export function useFunctionTags(arn: string) {
  return useQuery({
    queryKey: lambdaKeys.tags(arn),
    queryFn: async (): Promise<Record<string, string>> => {
      const r = await lambda.send(new ListTagsCommand({ Resource: arn }));
      return r.Tags ?? {};
    },
    enabled: !!arn,
  });
}

export function useSaveFunctionTags(arn: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      add: Record<string, string>;
      removeKeys: string[];
    }) => {
      if (input.removeKeys.length > 0) {
        await lambda.send(
          new UntagResourceCommand({
            Resource: arn,
            TagKeys: input.removeKeys,
          }),
        );
      }
      if (Object.keys(input.add).length > 0) {
        await lambda.send(
          new TagResourceCommand({ Resource: arn, Tags: input.add }),
        );
      }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: lambdaKeys.tags(arn) }),
  });
}
