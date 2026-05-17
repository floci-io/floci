import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useImages } from '@/hooks/ec2/useImages';

export default function AmisTab() {
  const { data: images = [], isLoading } = useImages();
  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Floci returns a static list of common AMIs plus its built-in mappings
        (e.g. <code>ami-amazonlinux2023</code> → Amazon Linux 2023). Unrecognised
        AMI IDs at launch fall back to Amazon Linux 2023.
      </p>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Image ID</TableHead>
            <TableHead>Name</TableHead>
            <TableHead>Owner</TableHead>
            <TableHead>Architecture</TableHead>
            <TableHead>Platform</TableHead>
            <TableHead>Description</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={6} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && images.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-center py-8 text-sm text-zinc-500">
                No AMIs.
              </TableCell>
            </TableRow>
          )}
          {images.map((i) => (
            <TableRow key={i.ImageId}>
              <TableCell className="font-mono text-xs">{i.ImageId}</TableCell>
              <TableCell>{i.Name ?? '—'}</TableCell>
              <TableCell className="text-zinc-600">{i.OwnerId ?? '—'}</TableCell>
              <TableCell>{i.Architecture ?? '—'}</TableCell>
              <TableCell>{i.PlatformDetails ?? i.Platform ?? '—'}</TableCell>
              <TableCell className="text-zinc-500 text-xs">
                {i.Description ?? '—'}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
