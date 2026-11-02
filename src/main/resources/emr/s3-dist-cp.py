"""Floci's s3-dist-cp, for an EMR step in the docker runner mode.

The EMR Serverless image a step runs in has neither s3-dist-cp nor MapReduce, so this copies through Hadoop's
FileSystem API in the step's container, under spark-submit, where S3 paths reach Floci's S3 and hdfs:// paths,
and paths without a scheme, the cluster's HDFS, as they do for a Spark step. It takes the options of the Release
Guide's "S3DistCp options" table and the generic -D option; planning what goes where is kept apart from the
copying, so it runs without Spark.
"""
import gzip
import json
import re
import sys
import uuid

VALUE_OPTIONS = {"src", "dest", "srcPattern", "groupBy", "targetSize", "outputCodec", "multipartUploadChunkSize",
                 "startingIndex", "outputManifest", "previousManifest", "s3Endpoint", "storageClass",
                 "srcPrefixesFile"}
FLAG_OPTIONS = {"appendToLastFile", "s3ServerSideEncryption", "deleteOnSuccess", "disableMultipartUpload",
                "numberFiles", "copyFromManifest", "requirePreviousManifest"}
# The codecs --outputCodec takes, by the class Hadoop knows each by; none decompresses.
OUTPUT_CODECS = {
    "gzip": "org.apache.hadoop.io.compress.GzipCodec",
    "gz": "org.apache.hadoop.io.compress.GzipCodec",
    "lzo": "com.hadoop.compression.lzo.LzopCodec",
    "lzop": "com.hadoop.compression.lzo.LzopCodec",
    "snappy": "org.apache.hadoop.io.compress.SnappyCodec",
    "none": None,
}
STORAGE_CLASSES = {"STANDARD", "REDUCED_REDUNDANCY"}
MIB = 1024 * 1024


class UsageError(Exception):
    pass


def parse(argv):
    """The options, as a dict, and the -D Hadoop settings, as a list of pairs."""
    options = {}
    settings = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "-D" or (arg.startswith("-D") and not arg.startswith("--")):
            setting = argv[i + 1] if arg == "-D" and i + 1 < len(argv) else arg[2:]
            i += 2 if arg == "-D" else 1
            key, equals, value = setting.partition("=")
            if not equals or not key:
                raise UsageError("-D takes a property=value setting, not " + setting)
            settings.append((key, value))
            continue
        if not arg.startswith("--"):
            raise UsageError("Unexpected argument " + arg)
        name, equals, value = arg[2:].partition("=")
        if name in FLAG_OPTIONS and name not in VALUE_OPTIONS:
            if name == "requirePreviousManifest":
                # True or false, in either form; alone it is true, its default.
                if not equals and i + 1 < len(argv) and argv[i + 1].lower() in ("true", "false"):
                    i += 1
                    value, equals = argv[i], "="
                if equals and value.lower() not in ("true", "false"):
                    raise UsageError("--requirePreviousManifest takes true or false, not " + value)
                options[name] = not equals or value.lower() == "true"
            elif equals:
                raise UsageError("--" + name + " takes no value")
            else:
                options[name] = True
            i += 1
        elif name in VALUE_OPTIONS:
            if not equals:
                if i + 1 >= len(argv):
                    raise UsageError("--" + name + " takes a value")
                value = argv[i + 1]
                i += 1
            options[name] = value
            i += 1
        else:
            raise UsageError("Unknown option --" + name)
    for required in ("src", "dest"):
        if not options.get(required):
            raise UsageError("--" + required + " is required")
    if "outputCodec" in options and options["outputCodec"].lower() not in OUTPUT_CODECS:
        raise UsageError("--outputCodec takes gzip, gz, lzo, lzop, snappy or none, not " + options["outputCodec"])
    if "storageClass" in options and options["storageClass"] not in STORAGE_CLASSES:
        raise UsageError("--storageClass takes STANDARD or REDUCED_REDUNDANCY, not " + options["storageClass"])
    for number in ("targetSize", "multipartUploadChunkSize", "startingIndex"):
        if number in options and not re.fullmatch(r"\d+", options[number]):
            raise UsageError("--" + number + " takes a whole number, not " + options[number])
    if "groupBy" in options and re.compile(options["groupBy"]).groups == 0:
        raise UsageError("The --groupBy regular expression " + options["groupBy"]
                         + " has no parenthetical group to name the concatenated files by")
    for location in (options["src"], options["dest"]):
        bucket = s3_bucket(location)
        if bucket is not None and "_" in bucket:
            raise UsageError("S3DistCp does not support Amazon S3 bucket names that contain the underscore"
                             " character: " + location)
    return options, settings


def s3_bucket(location):
    match = re.match(r"(?i)s3[an]?://([^/]*)", location)
    return match.group(1) if match else None


def prefix_start(prefix, path):
    """What a file's path starts with to be under a --srcPrefixesFile line, from the line and the path Hadoop's Path
    makes of it, which drops a trailing slash: a line ending in one names that folder alone, so the slash comes
    back; any other is a prefix as S3 lists one, which logs/day-old/ matches as logs/day/ does."""
    return path.rstrip("/") + "/" if prefix.endswith("/") else path


def split_name(name, extension):
    """A file name as its stem and the codec extension it ends in, if it does."""
    if extension and name.endswith(extension) and len(name) > len(extension):
        return name[:-len(extension)], extension
    return name, ""


def plan(files, options, extensions, output_extension):
    """What the copy writes: a list of outputs, each a target path relative to --dest and the source files that
    go into it, in order. files are (path, relative path, size) triples, relative to --src; extensions maps a
    source's path to the extension of the codec its name ends in ("" for none); output_extension is
    --outputCodec's extension, "" for none, or None when it is not given, which leaves each file's compression
    as it is and gives a group the one its files share (see group_extension)."""
    pattern = re.compile(options["srcPattern"]) if "srcPattern" in options else None
    files = [f for f in sorted(files, key=lambda f: f[1]) if pattern is None or pattern.fullmatch(f[0])]
    outputs = []
    if "groupBy" in options:
        group_by = re.compile(options["groupBy"])
        groups = {}
        for f in files:
            match = group_by.fullmatch(f[0])
            if match:
                groups.setdefault("".join(g or "" for g in match.groups()), []).append(f)
        target_size = int(options["targetSize"]) * MIB if "targetSize" in options else None
        for name, members in groups.items():
            folder = members[0][1].rpartition("/")[0]
            parts = split_parts(members, target_size)
            extension = group_extension(members, extensions, output_extension)
            # A name that ends in the extension already keeps one, the part's number before it.
            name = split_name(name, extension)[0]
            for index, part in enumerate(parts):
                stem = name + (str(index) if len(parts) > 1 else "")
                outputs.append((join(folder, stem + extension), part))
    else:
        for f in files:
            folder, _, name = f[1].rpartition("/")
            if output_extension is not None:
                name = split_name(name, extensions.get(f[0], ""))[0] + output_extension
            outputs.append((join(folder, name), [f]))
    if options.get("numberFiles"):
        start = int(options.get("startingIndex", "0"))
        numbered = []
        for index, (target, sources) in enumerate(outputs):
            folder, _, name = target.rpartition("/")
            numbered.append((join(folder, str(start + index) + name), sources))
        outputs = numbered
    return outputs


def check_targets(outputs, dest, manifest, qualify):
    """Refuses a copy that would lose data before it writes anything: an output, the manifest included, outside
    --dest, through a .. in a manifest's baseName say, which Hadoop's paths resolve; one with no file name; two at
    the same path, the second replacing the first; or one over one of its own sources, which creating it would empty
    before it is read. qualify gives a location as its file system names it."""
    sources = {qualify(f[0]) for _, files in outputs for f in files}
    targets = [(target, ", ".join(f[1] for f in files)) for target, files in outputs]
    if manifest is not None:
        targets.append((manifest, "the manifest"))
    written = {}
    for target, what in targets:
        if ".." in target.split("/"):
            raise UsageError(target + " leads out of --dest " + dest)
        if target.rpartition("/")[2] in ("", "."):
            # A group whose groups matched nothing, say: Hadoop's paths would make the file its folder.
            raise UsageError(what + " would be written to " + located(dest, target) + ", which names no file")
        location = qualify(located(dest, target))
        if location in written:
            raise UsageError(written[location] + " and " + what + " would both be written to " + location)
        if location in sources:
            raise UsageError(what + " would be written over its source " + location)
        written[location] = what


def replace(fs, staged, target, aside):
    """Puts staged in target's place through renames, as Hadoop's FileSystem has no public rename that replaces an
    existing file: target steps aside first, to aside, a name of its own, and comes back if staged cannot take its
    place, so it is never lost; a step stopped between the renames leaves both under their own names."""
    if not fs.rename(target, aside):
        raise IOError("Could not move " + str(target) + " aside to replace it")
    if not fs.rename(staged, target):
        restored = fs.rename(aside, target)
        raise IOError("Could not rename " + str(staged) + " to " + str(target)
                      + ("" if restored else "; what it held is at " + str(aside)))
    fs.delete(aside, False)


def group_extension(members, extensions, output_extension):
    """The extension of a group's file: --outputCodec's, or without it the one its files share, none when they
    differ. The copy decompresses each file and writes the group as that extension's codec, so its bytes are one
    stream its name tells the format of, whatever its files' compression."""
    if output_extension is not None:
        return output_extension
    shared = {extensions.get(f[0], "") for f in members}
    return shared.pop() if len(shared) == 1 else ""


def split_parts(members, target_size):
    """A group's files as the parts --targetSize breaks it into, each as close to the size as whole files make it."""
    if target_size is None:
        return [members]
    parts = [[]]
    size = 0
    for f in members:
        if parts[-1] and size + f[2] > target_size:
            parts.append([])
            size = 0
        parts[-1].append(f)
        size += f[2]
    return parts


def files_word(count):
    return "file" if count == 1 else "files"


def join(folder, name):
    return folder + "/" + name if folder else name


def located(folder, relative):
    # One string, so a colon in a file's name is not read as a scheme.
    return folder.rstrip("/") + "/" + relative


def manifest_line(path, base_name, src_dir, size):
    return json.dumps({"path": path, "baseName": base_name, "srcDir": src_dir, "size": size},
                      separators=(",", ":"))


class Copier:
    """The copy itself, through Hadoop's FileSystem API in Spark's JVM."""

    def __init__(self, options, settings):
        from pyspark.sql import SparkSession
        self.options = options
        self.spark = SparkSession.builder.appName("S3DistCp").getOrCreate()
        self.jvm = self.spark._jvm
        self.conf = self.spark._jsc.hadoopConfiguration()
        for key, value in settings:
            self.conf.set(key, value)
        if options.get("s3ServerSideEncryption"):
            self.conf.set("fs.s3a.encryption.algorithm", "SSE-S3")
        if "storageClass" in options:
            self.conf.set("fs.s3a.create.storage.class", options["storageClass"].lower())
        if options.get("disableMultipartUpload"):
            self.conf.set("fs.s3a.multipart.uploads.enabled", "false")
        if "multipartUploadChunkSize" in options:
            self.conf.set("fs.s3a.multipart.size", options["multipartUploadChunkSize"] + "M")
        self.codecs = self.jvm.org.apache.hadoop.io.compress.CompressionCodecFactory(self.conf)

    def path(self, location):
        path = self.jvm.org.apache.hadoop.fs.Path(location)
        fs = path.getFileSystem(self.conf)
        return fs, fs.makeQualified(path)

    def under(self, folder, relative):
        return self.jvm.org.apache.hadoop.fs.Path(located(folder.toString(), relative))

    def files(self, fs, root):
        """Every file under root, or root itself when it is one, as (path, relative path, size)."""
        status = fs.getFileStatus(root)
        if status.isFile():
            return [(status.getPath().toString(), status.getPath().getName(), status.getLen())]
        base = root.toUri().getPath().rstrip("/") + "/"
        found = []
        listing = fs.listFiles(root, True)
        while listing.hasNext():
            status = listing.next()
            found.append((status.getPath().toString(), status.getPath().toUri().getPath()[len(base):],
                          status.getLen()))
        return found

    def prefixed(self, src_fs, src, prefixes):
        """The files under each prefix, relative to --src."""
        base = src.toUri().getPath().rstrip("/") + "/"
        found = {}
        for prefix in prefixes:
            fs, path = self.path(prefix)
            start = prefix_start(prefix, path.toUri().getPath())
            folder = path if start.endswith("/") or path.getParent() is None else path.getParent()
            listing = fs.listFiles(folder, True)
            while listing.hasNext():
                status = listing.next()
                file_path = status.getPath().toUri().getPath()
                if file_path.startswith(start):
                    relative = file_path[len(base):] if file_path.startswith(base) else status.getPath().getName()
                    found[status.getPath().toString()] = (status.getPath().toString(), relative, status.getLen())
        return list(found.values())

    def read_lines(self, location, gzipped):
        fs, path = self.path(location)
        stream = fs.open(path)
        try:
            data = bytes(self.jvm.org.apache.commons.io.IOUtils.toByteArray(stream))
        finally:
            stream.close()
        text = gzip.decompress(data).decode("utf-8") if gzipped else data.decode("utf-8")
        return [line.strip() for line in text.splitlines() if line.strip()]

    def write_bytes(self, fs, path, data):
        out = fs.create(path, True)
        try:
            out.write(bytearray(data))
        finally:
            out.close()

    def extension_of(self, path):
        codec = self.codecs.getCodec(self.jvm.org.apache.hadoop.fs.Path(path))
        return codec.getDefaultExtension() if codec is not None else ""

    def run(self):
        options = self.options
        src_fs, src = self.path(options["src"])
        dest_fs, dest = self.path(options["dest"])
        manifest = []
        previous = []
        if "previousManifest" in options:
            prev_fs, prev = self.path(options["previousManifest"])
            if prev_fs.exists(prev):
                previous = [json.loads(line) for line in self.read_lines(options["previousManifest"], True)]
            elif options.get("requirePreviousManifest", True):
                raise UsageError("The previous manifest " + options["previousManifest"] + " does not exist")
        if options.get("copyFromManifest"):
            if "previousManifest" not in options:
                raise UsageError("--copyFromManifest needs --previousManifest")
            files = [(entry["path"], entry["baseName"], int(entry.get("size", 0))) for entry in previous]
        else:
            if "srcPrefixesFile" in options:
                files = self.prefixed(src_fs, src, self.read_lines(options["srcPrefixesFile"], False))
            elif src_fs.exists(src):
                files = self.files(src_fs, src)
            else:
                raise UsageError("--src " + options["src"] + " does not exist")
            copied_before = {entry["baseName"] for entry in previous}
            files = [f for f in files if f[1] not in copied_before]
            manifest.extend(manifest_line(e["path"], e["baseName"], e.get("srcDir", ""), int(e.get("size", 0)))
                            for e in previous)
        codec_name = options.get("outputCodec", "").lower() or None
        output_codec = None
        output_extension = None
        if codec_name is not None:
            output_extension = ""
            if OUTPUT_CODECS[codec_name] is not None:
                output_codec = self.codecs.getCodecByClassName(OUTPUT_CODECS[codec_name])
                if output_codec is None:
                    raise UsageError("The " + codec_name + " codec is not available")
                output_extension = output_codec.getDefaultExtension()
        extensions = {f[0]: self.extension_of(f[0]) for f in files}
        outputs = plan(files, options, extensions, output_extension)
        check_targets(outputs, dest.toString(), options.get("outputManifest"),
                      lambda location: self.path(location)[1].toString())
        copied_bytes = 0
        for target, sources in outputs:
            target_path = self.under(dest, target)
            codec, recompress = output_codec, codec_name is not None
            if codec_name is None and "groupBy" in options:
                # A group's file is in the format its name's extension gives (see group_extension).
                codec, recompress = self.codecs.getCodec(target_path), True
            copied_bytes += self.write(dest_fs, target_path, sources, codec, recompress)
            for f in sources:
                manifest.append(manifest_line(f[0], f[1], src.toString(), f[2]))
        if "outputManifest" in options:
            data = gzip.compress(("\n".join(manifest) + "\n").encode("utf-8") if manifest else b"")
            self.write_bytes(dest_fs, self.under(dest, options["outputManifest"]), data)
        if options.get("deleteOnSuccess"):
            for _, sources in outputs:
                for f in sources:
                    fs, path = self.path(f[0])
                    fs.delete(path, False)
        copied = sum(len(sources) for _, sources in outputs)
        print("S3DistCp: copied %d %s (%d bytes) from %s to %s into %d %s"
              % (copied, files_word(copied), copied_bytes, src.toString(), dest.toString(), len(outputs),
                 files_word(len(outputs))))

    def write(self, dest_fs, target, sources, output_codec, recompress):
        """Writes sources, one after the other, to target, decompressed and compressed again when --outputCodec
        is given, and returns the bytes read. With --appendToLastFile they follow what target holds, through a
        copy that takes its place (see replace), as neither S3 nor the cluster's HDFS, a local folder, can append
        in place; the copy has a name no other file has, and goes unless it took target's place."""
        new_path = self.jvm.org.apache.hadoop.fs.Path
        staged = None
        if self.options.get("appendToLastFile") and dest_fs.exists(target):
            staged = new_path(target.toString() + "." + uuid.uuid4().hex + "._COPYING_")
            raw = dest_fs.create(staged, False)
        else:
            raw = dest_fs.create(target, True)
        replaced = False
        try:
            read = self.fill(raw, dest_fs, target if staged is not None else None, sources, output_codec, recompress)
            if staged is not None:
                replace(dest_fs, staged, target,
                        new_path(target.toString() + "." + uuid.uuid4().hex + "._REPLACED_"))
            replaced = True
        finally:
            if staged is not None and not replaced:
                dest_fs.delete(staged, False)
        return read

    def fill(self, raw, dest_fs, existing, sources, output_codec, recompress):
        """Writes what existing holds, when there is one, then sources, to raw, and closes it; returns the bytes
        read from sources."""
        io_utils = self.jvm.org.apache.hadoop.io.IOUtils
        out = raw
        read = 0
        try:
            if existing is not None:
                stream = dest_fs.open(existing)
                try:
                    io_utils.copyBytes(stream, raw, self.conf, False)
                finally:
                    stream.close()
            if output_codec is not None:
                out = output_codec.createOutputStream(raw)
            for f in sources:
                fs, path = self.path(f[0])
                stream = fs.open(path)
                try:
                    source_codec = self.codecs.getCodec(path) if recompress else None
                    if source_codec is not None:
                        stream = source_codec.createInputStream(stream)
                    io_utils.copyBytes(stream, out, self.conf, False)
                finally:
                    stream.close()
                read += f[2]
        finally:
            out.close()
        return read


def main(argv):
    try:
        options, settings = parse(argv)
        Copier(options, settings).run()
    except UsageError as e:
        print("S3DistCp: " + str(e), file=sys.stderr)
        return 1
    except Exception as e:
        # A copy Hadoop failed, of a source that is not there say: what was copied stays, as S3DistCp leaves it.
        java = getattr(e, "java_exception", None)
        print("S3DistCp: " + (java.toString() if java is not None else str(e)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
