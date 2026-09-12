package io.github.hectorvent.floci.services.rdsdata;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers the JDBC driver classes that are instantiated by name in a native image.
 * DriverManager loads the drivers through ServiceLoader, MySQL Connector/J creates its
 * connection URL, socket factory, logger and exceptions through Class.forName, and the
 * PostgreSQL driver creates PGobject subtypes reflectively when reading result sets.
 */
@RegisterForReflection(classNames = {
    "com.mysql.cj.jdbc.Driver",
    "com.mysql.cj.conf.url.SingleConnectionUrl",
    "com.mysql.cj.protocol.StandardSocketFactory",
    "com.mysql.cj.log.StandardLogger",
    "com.mysql.cj.exceptions.AssertionFailedException",
    "com.mysql.cj.exceptions.CJCommunicationsException",
    "com.mysql.cj.exceptions.CJConnectionFeatureNotAvailableException",
    "com.mysql.cj.exceptions.CJException",
    "com.mysql.cj.exceptions.CJOperationNotSupportedException",
    "com.mysql.cj.exceptions.CJPacketTooBigException",
    "com.mysql.cj.exceptions.CJTimeoutException",
    "com.mysql.cj.exceptions.ClosedOnExpiredPasswordException",
    "com.mysql.cj.exceptions.ConnectionIsClosedException",
    "com.mysql.cj.exceptions.DataConversionException",
    "com.mysql.cj.exceptions.DataReadException",
    "com.mysql.cj.exceptions.DataTruncationException",
    "com.mysql.cj.exceptions.FeatureNotAvailableException",
    "com.mysql.cj.exceptions.InvalidConnectionAttributeException",
    "com.mysql.cj.exceptions.NumberOutOfRange",
    "com.mysql.cj.exceptions.OperationCancelledException",
    "com.mysql.cj.exceptions.PasswordExpiredException",
    "com.mysql.cj.exceptions.PropertyNotModifiableException",
    "com.mysql.cj.exceptions.RSAException",
    "com.mysql.cj.exceptions.SSLParamsException",
    "com.mysql.cj.exceptions.StatementIsClosedException",
    "com.mysql.cj.exceptions.UnableToConnectException",
    "com.mysql.cj.exceptions.UnsupportedConnectionStringException",
    "com.mysql.cj.exceptions.WrongArgumentException",
    "org.postgresql.Driver",
    "org.postgresql.util.PGobject",
    "org.postgresql.util.PGInterval",
    "org.postgresql.util.PGmoney",
    "org.postgresql.geometric.PGbox",
    "org.postgresql.geometric.PGcircle",
    "org.postgresql.geometric.PGline",
    "org.postgresql.geometric.PGlseg",
    "org.postgresql.geometric.PGpath",
    "org.postgresql.geometric.PGpoint",
    "org.postgresql.geometric.PGpolygon"
}, methods = false, fields = false, ignoreNested = true)
public class JdbcDriverNativeSupport {}
