package io.github.hectorvent.floci.services.iam;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.w3c.dom.Element;

import java.io.OutputStream;

/**
 * Native image substitutions for the XSLT and XPath transforms of the JDK XML signature code.
 * SAMLAssertionVerifier rejects every transform except enveloped signature and exclusive
 * canonicalization before it validates, as AWS does, so these never run. Every registered transform
 * is reachable through one virtual call, so without these the XSLT compiler and the XPath engine
 * stay in the image.
 */
final class XmlSignatureTransformSubstitutions {

    static final String UNAVAILABLE = " transform is not supported";

    private XmlSignatureTransformSubstitutions() {
    }
}

@TargetClass(className = "com.sun.org.apache.xml.internal.security.signature.XMLSignatureInput")
final class Target_XMLSignatureInput {
}

@TargetClass(className = "com.sun.org.apache.xml.internal.security.transforms.implementations.TransformXSLT")
final class Target_TransformXSLT {

    @Substitute
    protected Target_XMLSignatureInput enginePerformTransform(Target_XMLSignatureInput input, OutputStream os,
                                                              Element transformElement, String baseURI,
                                                              boolean secureValidation) {
        throw new UnsupportedOperationException("XSLT" + XmlSignatureTransformSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.sun.org.apache.xml.internal.security.transforms.implementations.TransformXPath")
final class Target_TransformXPath {

    @Substitute
    protected Target_XMLSignatureInput enginePerformTransform(Target_XMLSignatureInput input, OutputStream os,
                                                              Element transformElement, String baseURI,
                                                              boolean secureValidation) {
        throw new UnsupportedOperationException("XPath" + XmlSignatureTransformSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.sun.org.apache.xml.internal.security.transforms.implementations.TransformXPath2Filter")
final class Target_TransformXPath2Filter {

    @Substitute
    protected Target_XMLSignatureInput enginePerformTransform(Target_XMLSignatureInput input, OutputStream os,
                                                              Element transformElement, String baseURI,
                                                              boolean secureValidation) {
        throw new UnsupportedOperationException("XPath Filter 2.0" + XmlSignatureTransformSubstitutions.UNAVAILABLE);
    }
}
