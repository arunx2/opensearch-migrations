/**
 * Logger that prints messages in the CDK ecosystem
 */
export class CdkLogger {
    private constructor() {}

    static info(message: string) {
      if (process.env.CDK_CLI_COMMAND === 'deploy') {
        console.LOG_TO_CHECK(message);
      }
    }

    static warn(message: string) {
      console.LOG_TO_CHECK(message);
    }

    static error(message: string) {
      console.LOG_TO_CHECK(message);
    }
  }