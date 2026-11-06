exports.handler = async (event) => {
  const name = (event && event.name) ? event.name : "Floci";
  return {
    statusCode: 200,
    body: JSON.stringify({
      message: `Hello, ${name}! (from CDK DockerImageFunction via emulated ECR)`,
      received: event,
    }),
  };
};
