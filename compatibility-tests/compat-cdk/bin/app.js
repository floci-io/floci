#!/usr/bin/env node
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const cdk = require("aws-cdk-lib");
const floci_stack_1 = require("../lib/floci-stack");
const app = new cdk.App();
new floci_stack_1.FlociTestStack(app, 'FlociTestStack', {
    env: {
        account: '000000000000',
        region: 'us-east-1',
    },
});
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiYXBwLmpzIiwic291cmNlUm9vdCI6IiIsInNvdXJjZXMiOlsiYXBwLnRzIl0sIm5hbWVzIjpbXSwibWFwcGluZ3MiOiI7OztBQUNBLG1DQUFtQztBQUNuQyxvREFBb0Q7QUFFcEQsTUFBTSxHQUFHLEdBQUcsSUFBSSxHQUFHLENBQUMsR0FBRyxFQUFFLENBQUM7QUFDMUIsSUFBSSw0QkFBYyxDQUFDLEdBQUcsRUFBRSxnQkFBZ0IsRUFBRTtJQUN4QyxHQUFHLEVBQUU7UUFDSCxPQUFPLEVBQUUsY0FBYztRQUN2QixNQUFNLEVBQUUsV0FBVztLQUNwQjtDQUNGLENBQUMsQ0FBQyIsInNvdXJjZXNDb250ZW50IjpbIiMhL3Vzci9iaW4vZW52IG5vZGVcbmltcG9ydCAqIGFzIGNkayBmcm9tICdhd3MtY2RrLWxpYic7XG5pbXBvcnQgeyBGbG9jaVRlc3RTdGFjayB9IGZyb20gJy4uL2xpYi9mbG9jaS1zdGFjayc7XG5cbmNvbnN0IGFwcCA9IG5ldyBjZGsuQXBwKCk7XG5uZXcgRmxvY2lUZXN0U3RhY2soYXBwLCAnRmxvY2lUZXN0U3RhY2snLCB7XG4gIGVudjoge1xuICAgIGFjY291bnQ6ICcwMDAwMDAwMDAwMDAnLFxuICAgIHJlZ2lvbjogJ3VzLWVhc3QtMScsXG4gIH0sXG59KTtcbiJdfQ==