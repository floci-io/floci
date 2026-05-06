@Path("/2015-03-31")
public class FixtureRestController {

    @POST
    @Path("/functions")
    public Response createFunction(CreateFunctionRequest request) {
        return null;
    }

    @GET
    @Path("/functions/{name}")
    public Response getFunction(@PathParam("name") String name) {
        return null;
    }

    @GET
    @Path("/functions")
    public Response listFunctions() {
        return null;
    }

    @PUT
    @Path("/functions/{name}/configuration")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateFunctionConfiguration(@PathParam("name") String name, ConfigRequest req) {
        return null;
    }

    @DELETE
    @Path("/functions/{name}")
    public Response deleteFunction(@PathParam("name") String name) {
        return null;
    }

    @PATCH
    @Path("/functions/{name}/url")
    public Response updateFunctionUrlConfig(@PathParam("name") String name) {
        return null;
    }

    public Response notAnEndpoint() {
        return null;
    }

    private void helperMethod() {
    }
}
