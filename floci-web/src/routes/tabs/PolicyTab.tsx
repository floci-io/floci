import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { JsonEditor } from '@/components/JsonEditor';
import { usePolicy, usePutPolicy } from '@/hooks/s3/usePolicy';

const SAMPLE = JSON.stringify(
  {
    Version: '2012-10-17',
    Statement: [
      {
        Sid: 'AllowPublicRead',
        Effect: 'Allow',
        Principal: '*',
        Action: ['s3:GetObject'],
        Resource: ['arn:aws:s3:::my-bucket/*'],
      },
    ],
  },
  null,
  2,
);

export default function PolicyTab() {
  const { bucket = '' } = useParams();
  const { data: serverPolicy } = usePolicy(bucket);
  const putPolicy = usePutPolicy(bucket);
  const [doc, setDoc] = useState('');

  useEffect(() => {
    if (serverPolicy) {
      try {
        setDoc(JSON.stringify(JSON.parse(serverPolicy), null, 2));
      } catch {
        setDoc(serverPolicy);
      }
    } else {
      setDoc('');
    }
  }, [serverPolicy]);

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Bucket policy document. Save an empty document to delete the policy.
      </p>
      <JsonEditor value={doc} onChange={setDoc} minHeight="320px" />
      <div className="flex gap-2">
        <Button
          disabled={putPolicy.isPending}
          onClick={async () => {
            if (doc.trim() !== '') {
              try {
                JSON.parse(doc);
              } catch {
                toast.error('Policy is not valid JSON');
                return;
              }
            }
            try {
              await putPolicy.mutateAsync(doc.trim() === '' ? null : doc);
              toast.success(doc.trim() === '' ? 'Policy deleted' : 'Policy saved');
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
          onClick={() => setDoc(SAMPLE)}
          type="button"
        >
          Load sample
        </Button>
        <Button
          variant="outline"
          onClick={() => setDoc(serverPolicy ?? '')}
          type="button"
        >
          Reset
        </Button>
      </div>
    </div>
  );
}
