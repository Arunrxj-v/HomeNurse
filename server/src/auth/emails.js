/**
 * Password-reset email delivery via a pluggable transport interface.
 *
 * Providers:
 *  - "console" (default): logs that a reset email was "sent". The raw reset
 *    token is logged ONLY when DEBUG_RESET_TOKENS=1 AND NODE_ENV is not
 *    production — it is never logged in production under any circumstance.
 *  - "smtp": real delivery through nodemailer using SMTP_* env vars. If
 *    nodemailer is unavailable (or SMTP_HOST is missing) the transport throws
 *    `smtp_not_configured: ...`, which the caller logs without failing the
 *    request (forgot-password always returns 202).
 */
export function createEmailSender({ config, logger = console, resetTtlSeconds } = {}) {
  let transporterPromise = null;

  async function getSmtpTransport() {
    if (!config.SMTP.HOST) {
      throw new Error('smtp_not_configured: SMTP_HOST is not set');
    }
    if (!transporterPromise) {
      transporterPromise = (async () => {
        let nodemailer;
        try {
          nodemailer = (await import('nodemailer')).default;
        } catch (err) {
          throw new Error(`smtp_not_configured: nodemailer is not installed (${err.message})`);
        }
        return nodemailer.createTransport({
          host: config.SMTP.HOST,
          port: config.SMTP.PORT,
          secure: config.SMTP.SECURE,
          auth: config.SMTP.USER ? { user: config.SMTP.USER, pass: config.SMTP.PASS } : undefined,
        });
      })();
    }
    return transporterPromise;
  }

  return {
    provider: config.EMAIL_PROVIDER,

    /**
     * Send a password-reset email. `rawToken` is the single-use reset token.
     * Failures are thrown; the caller decides whether they are fatal.
     */
    async sendResetEmail(to, rawToken) {
      const ttl = resetTtlSeconds ?? config.RESET_TOKEN_TTL;
      if (config.EMAIL_PROVIDER === 'smtp') {
        const transport = await getSmtpTransport();
        await transport.sendMail({
          from: config.SMTP.FROM,
          to,
          subject: 'Reset your HomeNurse password',
          text:
            `Someone requested a password reset for this HomeNurse account.\n\n` +
            `Use this token to choose a new password (valid for ${Math.round(ttl / 60)} minutes):\n\n` +
            `  ${rawToken}\n\n` +
            `If you did not request this, you can ignore this email.`,
        });
        logger.info?.(`password-reset email=${to} sent via smtp`);
        return;
      }

      // console provider
      if (config.DEBUG_RESET_TOKENS && !config.isProduction) {
        logger.info?.(`password-reset email=${to} token=${rawToken}`);
      } else {
        logger.info?.(`password-reset email=${to} token=<redacted>`);
      }
    },
  };
}
