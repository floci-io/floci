import {
  ListTagsForResourceCommand,
  TagResourceCommand,
  UntagResourceCommand,
  type Tag,
} from '@aws-sdk/client-scheduler';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { scheduler } from '@/lib/schedulerClient';
import { schedulerKeys } from '@/lib/queryKeys';

export function useScheduleGroupTags(arn: string) {
  return useQuery({
    queryKey: schedulerKeys.groupTags(arn),
    queryFn: async (): Promise<Tag[]> => {
      const r = await scheduler.send(
        new ListTagsForResourceCommand({ ResourceArn: arn }),
      );
      return r.Tags ?? [];
    },
    enabled: !!arn,
  });
}

export function useSaveScheduleGroupTags(arn: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      addOrUpdate: Tag[];
      removeKeys: string[];
    }) => {
      if (input.removeKeys.length > 0) {
        await scheduler.send(
          new UntagResourceCommand({
            ResourceArn: arn,
            TagKeys: input.removeKeys,
          }),
        );
      }
      if (input.addOrUpdate.length > 0) {
        await scheduler.send(
          new TagResourceCommand({
            ResourceArn: arn,
            Tags: input.addOrUpdate,
          }),
        );
      }
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: schedulerKeys.groupTags(arn) }),
  });
}
