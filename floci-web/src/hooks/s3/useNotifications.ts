import {
  GetBucketNotificationConfigurationCommand,
  PutBucketNotificationConfigurationCommand,
  type NotificationConfiguration,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export function useNotifications(bucket: string) {
  return useQuery({
    queryKey: qk.notifications(bucket),
    queryFn: async (): Promise<NotificationConfiguration> => {
      const r = await s3.send(
        new GetBucketNotificationConfigurationCommand({ Bucket: bucket }),
      );
      return {
        TopicConfigurations: r.TopicConfigurations ?? [],
        QueueConfigurations: r.QueueConfigurations ?? [],
        LambdaFunctionConfigurations: r.LambdaFunctionConfigurations ?? [],
        EventBridgeConfiguration: r.EventBridgeConfiguration,
      };
    },
    enabled: !!bucket,
  });
}

export function usePutNotifications(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (cfg: NotificationConfiguration) => {
      await s3.send(
        new PutBucketNotificationConfigurationCommand({
          Bucket: bucket,
          NotificationConfiguration: cfg,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.notifications(bucket) }),
  });
}
