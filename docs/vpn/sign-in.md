# Sign-in

A user signs in to HomeGAP with a HomeGAP password or through an OpenID Connect provider. This page tells you how to configure the two methods and what a user sees.

## The address of the sign-in

`sign_in.url` is the address of the sign-in. Your users enter it in the Pangolin apps, and the browser opens the sign-in page there. It must start with `https://`.

```yaml
sign_in:
  url: https://vpn.example.net
```

The `homegap-sign-in` container of the homelab stack serves the sign-in. HomeGAP treats the name as a public service. It gives the name a route on each node, a place in the certificate of the nodes and its DNS records. You add no label.

<!-- FACT-CHECK: whether sign_in.url can have a name outside `domain` and the extra domains. The source shows only names under the domain. -->

The sign-in container reads the `sign_in` part of the config only when it starts. After each change of that part, or of the client secret, recreate the container:

```bash
docker compose up -d --force-recreate homegap-sign-in
```

## Passwords

With `sign_in.passwords: true` (the default), a user signs in with an email address and a HomeGAP password.

- You create the user and give the password to the person. There is no page where a person registers, and there are no invitations. See [Users, machines and devices](access.md).
- A password that you choose has 12 characters or more. If you choose none, HomeGAP makes one.
- HomeGAP shows a new password one time and keeps only a hash of it (PBKDF2 with SHA-256, 600 000 iterations).
- A user cannot change a password alone. To replace one, click **New password** on the **Access** page, or run `homegap user password`.
- After 5 wrong passwords for one user or from one address in 15 minutes, the sign-in refuses more attempts for that time.
- A HomeGAP password has no second factor. If you need one, use a provider.

## OpenID Connect

With `sign_in.oidc`, the sign-in page has a button for your provider, for example Authelia. HomeGAP uses the authorization code flow with PKCE (S256).

The provider proves the email address of the user. The user must exist in HomeGAP with the same email address, and HomeGAP gives the roles. HomeGAP creates no user from the provider, and the groups of the provider do not become roles.

| Key | Default | Meaning |
| --- | --- | --- |
| `sign_in.oidc.issuer` | None | The address of the provider. It must start with `https://`. HomeGAP reads `<issuer>/.well-known/openid-configuration`. |
| `sign_in.oidc.client_id` | None | The id of the client that you create at the provider. |
| `sign_in.oidc.client_secret_env` | `HOMEGAP_OIDC_CLIENT_SECRET` | The environment variable of the sign-in container that holds the client secret. |
| `sign_in.oidc.name` | `single sign-on` | The name on the button: "Sign in with <name>". |
| `sign_in.oidc.scopes` | `[openid, email, profile]` | The scopes that HomeGAP asks for. The list must have `openid`. HomeGAP needs the email address, so keep `email`. |

`sign_in.oidc` needs `sign_in.url`. The redirect address that you give the provider is `<url>/auth/oidc/callback`.

### Configure Authelia

1. Choose a client secret, a long random text.
2. Add a client to the configuration of Authelia. Use the redirect address of your sign-in.

    ```yaml
    identity_providers:
      oidc:
        clients:
          - client_id: homegap
            client_name: HomeGAP
            client_secret: '<the digest of the client secret>'
            public: false
            authorization_policy: one_factor
            consent_mode: implicit
            redirect_uris:
              - https://vpn.example.net/auth/oidc/callback
            scopes: [openid, email, profile]
            token_endpoint_auth_method: client_secret_basic
            require_pkce: true
            pkce_challenge_method: S256
    ```

3. Restart Authelia.
4. Add the client secret to `.env` of the homelab stack:

    ```text
    HOMEGAP_OIDC_CLIENT_SECRET=<the client secret>
    ```

5. Add the provider to the config of HomeGAP:

    ```yaml
    sign_in:
      url: https://vpn.example.net
      oidc:
        name: Authelia
        issuer: https://auth.example.net
        client_id: homegap
    ```

6. Recreate the sign-in container:

    ```bash
    docker compose up -d --force-recreate homegap-sign-in
    ```

7. Make sure that each person has a HomeGAP user with the email address that Authelia holds.

HomeGAP sends the client secret with HTTP basic authentication, thus the client needs `client_secret_basic`. Authelia stores a digest of the secret. For the command that makes the digest, see the Authelia documentation.

The policy of the client in Authelia decides if a person needs a second factor.

<!-- FACT-CHECK: the harness tests Authelia 4.39 with the policy `one_factor` only. A sign-in with `two_factor` is not confirmed. -->

If the variable of the client secret is empty, the sign-in container does not start. Its log names the variable.

### Accept only the provider

To remove the password form from the page, set `passwords` to `false`:

```yaml
sign_in:
  url: https://vpn.example.net
  passwords: false
  oidc:
    name: Authelia
    issuer: https://auth.example.net
    client_id: homegap
```

HomeGAP refuses a config with `passwords: false` and no `oidc`.

!!! warning "Caution"
    A node cannot use the provider. With `passwords: false`, no user can sign in while the homelab is down. See [With the homelab down](index.md#with-the-homelab-down).

## What a user sees

1. The app shows a code of 8 characters and opens the sign-in page in the browser.
2. The page has the title **Sign in** and the text "A device asks for access to your HomeGAP."
3. The page lists the **Device**, the **App**, the **Code** and **Your address**, the address of the browser.
4. The user compares the code with the code in the app.
5. The user clicks **Sign in with Authelia**, or types the **Email** and the **Password** and clicks **Sign in and approve the device**.
6. The page **Device approved** shows "You are signed in as" and the email address. The browser opens the app again. The link **Open the app** does the same.

<!-- SCREENSHOT: docs/img/sign-in/01-page.png | The sign-in page with the device, the code, the provider button and the password form -->

If a person opens the page with no code, the page asks for the code that the device shows.

A code is valid for 10 minutes. A session is valid for 30 days. After that, the app asks the user to sign in again.

### Messages of the page

| Message | Cause |
| --- | --- |
| "The email or the password is not correct." | The password is wrong, or HomeGAP has no user with this address. |
| "You have no HomeGAP user with this email address. Ask your admin." | The provider accepts the person, and HomeGAP has no user with that address. |
| "Too many attempts. Try again in 15 minutes." | The sign-in counts 5 wrong passwords in 15 minutes for the user or the address. |
| "This code is not known, or it expired." | The code is older than 10 minutes, or it has a typing error. Start the sign-in in the app again. |
| "The sign-in provider does not answer. Try again in a moment." | The sign-in container cannot reach the provider. |
| "The provider gave no email address. Add the scope 'email'." | `sign_in.oidc.scopes` does not have `email`, or the client at the provider does not permit it. |
