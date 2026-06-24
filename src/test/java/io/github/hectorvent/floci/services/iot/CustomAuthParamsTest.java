package io.github.hectorvent.floci.services.iot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomAuthParamsTest {

    @Test
    void parsesAwsDeviceSdkStyleUsername() {
        String username = "mydevice?x-amz-customauthorizer-name=MyAuthorizer"
                + "&x-amz-customauthorizer-signature=ABC%2BDEF%3D&MyTokenKey=the-token-value";
        CustomAuthParams params = CustomAuthParams.parse(username);

        assertEquals("mydevice", params.baseUsername());
        assertEquals("MyAuthorizer", params.authorizerName());
        assertEquals("ABC+DEF=", params.signature());
        assertEquals("the-token-value", params.token("MyTokenKey"));
    }

    @Test
    void parsesUsernameWithoutBaseSegment() {
        String username = "?x-amz-customauthorizer-name=A&Tok=t";
        CustomAuthParams params = CustomAuthParams.parse(username);

        assertEquals("", params.baseUsername());
        assertEquals("A", params.authorizerName());
        assertEquals("t", params.token("Tok"));
    }

    @Test
    void plainUsernameHasNoParams() {
        CustomAuthParams params = CustomAuthParams.parse("plainuser");

        assertEquals("plainuser", params.baseUsername());
        assertNull(params.authorizerName());
        assertNull(params.signature());
        assertNull(params.token("anything"));
    }

    @Test
    void nullUsernameYieldsEmpty() {
        CustomAuthParams params = CustomAuthParams.parse(null);

        assertEquals("", params.baseUsername());
        assertNull(params.authorizerName());
    }

    @Test
    void tokenLookupRequiresKeyName() {
        CustomAuthParams params = CustomAuthParams.parse("u?x-amz-customauthorizer-name=A&K=v");
        assertNull(params.token(null));
        assertEquals("v", params.token("K"));
    }
}
