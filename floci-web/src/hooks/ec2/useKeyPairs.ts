import {
  CreateKeyPairCommand,
  DeleteKeyPairCommand,
  DescribeKeyPairsCommand,
  ImportKeyPairCommand,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useKeyPairs() {
  return useQuery({
    queryKey: ec2Keys.keyPairs(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeKeyPairsCommand({}));
      return r.KeyPairs ?? [];
    },
  });
}

export function useCreateKeyPair() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (name: string) => {
      const r = await ec2.send(new CreateKeyPairCommand({ KeyName: name }));
      return r;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.keyPairs() }),
  });
}

export function useImportKeyPair() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { name: string; publicKey: string }) => {
      const material = new TextEncoder().encode(input.publicKey);
      await ec2.send(
        new ImportKeyPairCommand({
          KeyName: input.name,
          PublicKeyMaterial: material,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.keyPairs() }),
  });
}

export function useDeleteKeyPair() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (name: string) => {
      await ec2.send(new DeleteKeyPairCommand({ KeyName: name }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.keyPairs() }),
  });
}
