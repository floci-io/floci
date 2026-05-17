import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import type { NotificationConfiguration } from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { JsonEditor } from '@/components/JsonEditor';
import {
  useNotifications,
  usePutNotifications,
} from '@/hooks/s3/useNotifications';

const EMPTY: NotificationConfiguration = {
  TopicConfigurations: [],
  QueueConfigurations: [],
  LambdaFunctionConfigurations: [],
};

export default function NotificationsTab() {
  const { bucket = '' } = useParams();
  const { data } = useNotifications(bucket);
  const putN = usePutNotifications(bucket);
  const [doc, setDoc] = useState(JSON.stringify(EMPTY, null, 2));

  useEffect(() => {
    setDoc(JSON.stringify(data ?? EMPTY, null, 2));
  }, [data]);

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        SQS / SNS / Lambda notification targets. ARNs are not validated against
        other Floci services — point them at queues / topics / functions you
        know exist.
      </p>
      <JsonEditor value={doc} onChange={setDoc} minHeight="320px" />
      <div className="flex gap-2">
        <Button
          disabled={putN.isPending}
          onClick={async () => {
            let parsed: NotificationConfiguration;
            try {
              parsed = JSON.parse(doc);
            } catch {
              toast.error('Invalid JSON');
              return;
            }
            try {
              await putN.mutateAsync(parsed);
              toast.success('Saved');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          Save
        </Button>
        <Button
          variant="outline"
          onClick={() => setDoc(JSON.stringify(data ?? EMPTY, null, 2))}
        >
          Reset
        </Button>
      </div>
    </div>
  );
}
