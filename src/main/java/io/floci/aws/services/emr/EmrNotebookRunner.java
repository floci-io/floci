package io.floci.aws.services.emr;

import java.util.List;

/**
 * What runs a notebook execution's notebook on its cluster, in the docker runner mode: a Python program of Floci's own,
 * in place of AWS's kernel, which no source names. It reads the notebook from S3 through the cluster's S3A, puts in the
 * execution's parameters as papermill does, a cell tagged {@code injected-parameters} after the one tagged
 * {@code parameters} (else first), as "You can pass different parameter values to a notebook with a parameterized
 * notebook cell", in place of any an earlier run put in, each parameter's name a Python variable's. It runs each code
 * cell in turn, every earlier output cleared first, in one namespace that holds {@code spark} and {@code sc}, keeping
 * its output, the value of its last expression and any error. A {@code %%sql} cell runs its body with
 * {@code spark.sql}, showing the result's first rows, and a {@code %%local} or {@code %%spark} one runs its body as
 * Python; another cell of sparkmagic's or IPython's commands is not run, nor is a {@code %} or {@code !} line of a cell
 * that is not Python as it stands. It stops at the first cell that raises, and writes the notebook as run, as
 * {@code <name>.ipynb} and as {@code <name>.html}, under the output folder, then exits {@link #CELL_RAISED}; any other
 * failure exits otherwise, the notebook perhaps not written.
 */
final class EmrNotebookRunner {

    // Where the program goes in the container, from the variable it comes in.
    static final String PROGRAM_PATH = "/tmp/floci-notebook-runner.py";
    static final String PROGRAM_VARIABLE = "FLOCI_NOTEBOOK_RUNNER";
    /** The program's exit code once a cell has raised and the notebook as run is written. */
    static final int CELL_RAISED = 3;

    // Python 3 and PySpark, which every EMR release's image has; no Jupyter, which they do not.
    static final String PROGRAM = """
            import ast
            import contextlib
            import html
            import io
            import json
            import keyword
            import sys
            import traceback

            from pyspark.sql import SparkSession


            def filesystem(spark, uri):
                jvm = spark.sparkContext._jvm
                path = jvm.org.apache.hadoop.fs.Path(uri)
                return path, path.getFileSystem(spark.sparkContext._jsc.hadoopConfiguration())


            def read(spark, uri):
                path, fs = filesystem(spark, uri)
                stream = fs.open(path)
                jvm = spark.sparkContext._jvm
                data = jvm.org.apache.commons.io.IOUtils.toByteArray(stream)
                stream.close()
                return bytes(data).decode("utf-8")


            def write(spark, uri, text):
                path, fs = filesystem(spark, uri)
                stream = fs.create(path, True)
                stream.write(bytearray(text.encode("utf-8")))
                stream.close()


            def lines(source):
                return source if isinstance(source, list) else str(source).splitlines(True)


            def tags(cell):
                return cell.get("metadata", {}).get("tags", [])


            def inject(cells, params):
                # A notebook an earlier run wrote has its parameters, which this run's replace.
                cells[:] = [cell for cell in cells if "injected-parameters" not in tags(cell)]
                if not params:
                    return
                source = ["# Parameters\\n"] + ["%s = %r\\n" % (name, value) for name, value in params.items()]
                cell = {"cell_type": "code", "execution_count": None, "metadata": {"tags": ["injected-parameters"]},
                        "outputs": [], "source": source}
                at = 0
                for index, existing in enumerate(cells):
                    if "parameters" in tags(existing):
                        at = index + 1
                        break
                cells.insert(at, cell)


            def cell_magic(text):
                stripped = text.lstrip()
                if not stripped.startswith("%%"):
                    return None, text
                first, _, body = stripped.partition("\\n")
                words = first[2:].split()
                return (words[0] if words else ""), body


            def python_source(text):
                # A line of IPython's or sparkmagic's commands is dropped only from a cell that is not Python as it
                # stands, so a line of a string or an expression that starts with % stays.
                try:
                    ast.parse(text)
                    return text
                except SyntaxError:
                    return "".join(line for line in text.splitlines(True)
                                   if not line.lstrip().startswith(("%", "!")))


            def run_cell(source, namespace, count):
                magic, text = cell_magic("".join(lines(source)))
                outputs = []
                if magic not in (None, "sql", "local", "spark"):
                    return outputs, None
                out, err = io.StringIO(), io.StringIO()
                failure = None
                value = None
                try:
                    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                        if magic == "sql":
                            frame = namespace["spark"].sql(text)
                            if frame.columns:
                                frame.show()
                        else:
                            tree = ast.parse(python_source(text), "<cell %d>" % count)
                            last = tree.body.pop() if tree.body and isinstance(tree.body[-1], ast.Expr) else None
                            exec(compile(tree, "<cell %d>" % count, "exec"), namespace)
                            if last is not None:
                                value = eval(compile(ast.Expression(last.value), "<cell %d>" % count, "eval"),
                                             namespace)
                except BaseException as e:
                    failure = e
                    value = None
                if out.getvalue():
                    outputs.append({"output_type": "stream", "name": "stdout", "text": lines(out.getvalue())})
                if err.getvalue():
                    outputs.append({"output_type": "stream", "name": "stderr", "text": lines(err.getvalue())})
                if failure is None and value is not None:
                    outputs.append({"output_type": "execute_result", "execution_count": count, "metadata": {},
                                    "data": {"text/plain": lines(repr(value))}})
                if failure is not None:
                    outputs.append({"output_type": "error", "ename": type(failure).__name__,
                                    "evalue": str(failure),
                                    "traceback": traceback.format_exception(type(failure), failure,
                                                                            failure.__traceback__)})
                return outputs, failure


            def page(name, cells):
                parts = ["<!DOCTYPE html><html><head><meta charset=\\"utf-8\\"><title>%s</title></head><body>"
                         % html.escape(name), "<h1>%s</h1>" % html.escape(name)]
                for cell in cells:
                    source = html.escape("".join(lines(cell.get("source", ""))))
                    if cell.get("cell_type") == "markdown":
                        parts.append("<div class=\\"markdown\\"><pre>%s</pre></div>" % source)
                        continue
                    if cell.get("cell_type") != "code":
                        continue
                    parts.append("<div class=\\"cell\\"><pre class=\\"source\\">%s</pre>" % source)
                    for output in cell.get("outputs", []):
                        if output["output_type"] == "error":
                            text = "".join(output["traceback"])
                        elif output["output_type"] == "stream":
                            text = "".join(output["text"])
                        else:
                            text = "".join(lines(output.get("data", {}).get("text/plain", "")))
                        parts.append("<pre class=\\"%s\\">%s</pre>" % (output["output_type"], html.escape(text)))
                    parts.append("</div>")
                parts.append("</body></html>")
                return "".join(parts)


            def parameters(params):
                values = json.loads(params) if params else {}
                if not isinstance(values, dict):
                    raise ValueError("NotebookParams is not a JSON object")
                for name in values:
                    if not name.isidentifier() or keyword.iskeyword(name):
                        raise ValueError("NotebookParams names %r, which is not a Python variable's name" % name)
                return values


            def main(source, folder, name, params):
                values = parameters(params)
                spark = SparkSession.builder.appName("notebook " + name).getOrCreate()
                notebook = json.loads(read(spark, source))
                cells = notebook.setdefault("cells", [])
                inject(cells, values)
                # A notebook an earlier run wrote has its outputs, which a cell this run does not reach keeps no more.
                for cell in cells:
                    if cell.get("cell_type") == "code":
                        cell["outputs"] = []
                        cell["execution_count"] = None
                namespace = {"__name__": "__main__", "spark": spark, "sc": spark.sparkContext}
                count = 0
                failed = None
                for cell in cells:
                    if cell.get("cell_type") != "code":
                        continue
                    count += 1
                    outputs, failure = run_cell(cell.get("source", ""), namespace, count)
                    cell["execution_count"] = count
                    cell["outputs"] = outputs
                    if failure is not None:
                        failed = "cell %d raised %s: %s" % (count, type(failure).__name__, failure)
                        break
                folder = folder.rstrip("/")
                write(spark, folder + "/" + name + ".ipynb", json.dumps(notebook, indent=1))
                write(spark, folder + "/" + name + ".html", page(name, cells))
                spark.stop()
                if failed is not None:
                    print("Notebook " + name + " failed: " + failed, file=sys.stderr)
                    sys.exit(@CELL_RAISED@)


            if __name__ == "__main__":
                main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else "")
            """.replace("@CELL_RAISED@", String.valueOf(CELL_RAISED));

    private EmrNotebookRunner() {
    }

    /**
     * What the cluster's container runs for an execution: the program written out from its variable, then
     * {@code spark-submit} with it, the notebook at {@code source}, the output {@code folder}, the notebook's
     * {@code name} and the execution's parameters, as a step's command runs. The execution's
     * {@code KERNEL_EXTRA_SPARK_OPTS} environment variable, as AWS's example passes
     * {@code "--conf spark.executor.instances=1"}, goes to {@code spark-submit} as its options.
     */
    static List<String> command(String source, String folder, String name, String params) {
        return List.of("bash", "-c", "printf '%s' \"$" + PROGRAM_VARIABLE + "\" > " + PROGRAM_PATH
                        + " && exec spark-submit $KERNEL_EXTRA_SPARK_OPTS \"$@\"", "floci-notebook",
                PROGRAM_PATH, source, folder, name, params != null ? params : "");
    }
}
