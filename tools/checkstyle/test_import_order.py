"""Exercise the repository's actual Checkstyle configuration on import fixtures."""

import copy
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
NS = "{http://maven.apache.org/POM/4.0.0}"


class ImportOrderTest(unittest.TestCase):
    def test_import_layout(self):
        fixtures = {
            "Ordered": ("""import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
""", False),
            "Wildcards": ("""import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
""", False),
            "StaticOnly": ("""import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
""", False),
            "MatcherOrder": ("""import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
""", True),
            "MockitoOrder": ("""import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
""", True),
            "TypeOrder": ("""import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
""", True),
            "JdkFirst": ("""import java.util.List;

import org.junit.jupiter.api.Test;
""", True),
            "StaticFirst": ("""import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
""", True),
            "MissingSeparation": ("""import org.junit.jupiter.api.Test;
import java.util.List;
""", True),
            "JavaBeforeJavax": ("""import java.util.List;
import javax.net.SocketFactory;
""", True),
            "JavaOnly": ("""import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
""", False),
            "JavaxOnly": ("""import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;

import static org.mockito.Mockito.mock;
""", False),
            "MissingJavaxSeparation": ("""import org.junit.jupiter.api.Test;
import javax.net.SocketFactory;
import java.util.List;
""", True),
            "UnsortedJavaAfterJavax": ("""import javax.net.SocketFactory;
import java.util.Map;
import java.util.List;
""", True),
            "MissingStaticSeparation": ("""import javax.net.SocketFactory;
import java.util.List;
import static org.mockito.Mockito.mock;
""", True),
            "JavaxInOtherPackage": ("""import org.example.javax.Widget;
import java.util.List;
""", True),
        }
        with tempfile.TemporaryDirectory(prefix="floci-import-order-") as directory:
            temporary = Path(directory)
            source = temporary / "src"
            source.mkdir()
            for name, (imports, _) in fixtures.items():
                (source / f"{name}.java").write_text(
                    f"package fixtures;\n\n{imports}\nclass {name} {{}}\n",
                    encoding="utf-8",
                )

            # Reuse the pinned plugin and engine versions from pom.xml.
            project = ET.parse(ROOT / "pom.xml").getroot()
            plugin = copy.deepcopy(next(
                item for item in project.findall(f"{NS}build/{NS}plugins/{NS}plugin")
                if item.findtext(f"{NS}artifactId") == "maven-checkstyle-plugin"
            ))
            configuration = plugin.find(f"{NS}configuration")
            configuration.find(f"{NS}configLocation").text = str(
                ROOT / "tools/checkstyle/checkstyle.xml"
            )
            configuration.find(f"{NS}propertyExpansion").text = (
                f"config_loc={ROOT / 'tools/checkstyle'}"
            )
            directories = configuration.find(f"{NS}sourceDirectories")
            directories.clear()
            ET.SubElement(directories, f"{NS}sourceDirectory").text = str(source)
            pom = ET.fromstring(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                '<modelVersion>4.0.0</modelVersion><groupId>io.github.hectorvent</groupId>'
                '<artifactId>import-order-fixtures</artifactId><version>1</version>'
                '<build><plugins/></build></project>'
            )
            pom.find(f"{NS}build/{NS}plugins").append(plugin)
            ET.register_namespace("", NS[1:-1])
            ET.ElementTree(pom).write(temporary / "pom.xml", encoding="utf-8")
            result = subprocess.run(
                [str(ROOT / "mvnw"), "-B", "-f", str(temporary / "pom.xml"),
                 "checkstyle:check"],
                cwd=ROOT, capture_output=True, text=True, timeout=180,
            )
            report = temporary / "target/checkstyle-result.xml"
            self.assertTrue(report.exists(), result.stdout + result.stderr)
            checked = {}
            diagnostics = {}
            for file in ET.parse(report).getroot().findall("file"):
                name = Path(file.get("name")).stem
                if name in fixtures:
                    errors = file.findall("error")
                    for error in errors:
                        self.assertTrue(error.get("source").endswith(".ImportOrderCheck"))
                    checked[name] = bool(errors)
                    diagnostics[name] = [error.attrib for error in errors]
            self.assertEqual({name: bad for name, (_, bad) in fixtures.items()}, checked, diagnostics)
            self.assertNotEqual(0, result.returncode, "Invalid import fixtures must fail Checkstyle")


if __name__ == "__main__":
    unittest.main()
