# Users and devices

The **Access** page manages the people who can sign in to the Pangolin apps, and their devices. This page tells you how to use it.

The page is available only in cluster mode. Its caption is "Access · Pangolin users and devices". You do not sign in to Pangolin as the admin.

<img src="../img/access/01-page.png" alt="The Access page with the app address, the Add a user form, the users and the devices">

## How changes reach Pangolin

All nodes share one database. Thus HomeGAP sends each change through the Pangolin of any node that it can reach. The page shows the node, for example "Read through na1's Pangolin". HomeGAP writes each change to the reconcile log.

The first time, HomeGAP gives its API key the permissions that the page needs. This takes one admin login.

## The Pangolin app address

The **Pangolin app address** panel shows `https://<client_hostname>.<domain>` and a QR code of the address. Click **Copy**, and give the address to your users. In the Pangolin app, they add it as a self-hosted server, or they scan the code with a phone. See [Connect the Pangolin apps](pangolin-apps.md).

## Add a user

1. Click **+ Add user**. The **Add a user** panel opens.
2. Type the email address in **Email**.
3. Under **Roles**, select one or more roles. A new user has the role with the least privilege by default.
4. Select how the user gets a password. Do one of the procedures below.

The panel shows "Pick at least one role" if you do not select a role.

### With a password

1. Select **Set a password now**. The text tells you "They can sign in at once. Share it with them privately."
2. In **Password**, type a password with at least 8 characters.
3. Click **Create user**.
4. Give the password to the user on a private channel.

### With an invite link

1. Clear **Set a password now**.
2. Click **Create invite link**. The panel shows the link.
3. Click **Copy link**.
4. Send the link to the person. The link is valid for 7 days. The person opens it and chooses a password.
5. Click **Done**.

<img src="../img/access/02-add-user.png" alt="The Add a user form with an email, a role and Set a password now selected" width="566px">
<img src="../img/access/03-invite-link.png" alt="The Add a user form with a new invite link and the Copy button" width="566px">

## Users

The **People** panel shows "Everyone who can sign in, on every node". Each row shows the name, the email address, the roles and the number of devices of a user. Click a user to open the user in the Inspector.

- To change the roles of a user, select the roles under **Roles** in the Inspector. Then click **Save**.
- To remove a user, click **Remove** in the Inspector. Then confirm. HomeGAP removes the user after 30 seconds. Until then, you can undo the removal. After the removal, the user can no longer sign in, and the devices of the user stop.

For the server admin account that HomeGAP creates, the Inspector shows **server admin**. You cannot change or remove this user here.

The **Open invitations** panel shows the invitations that nobody has used yet, with the time until each one expires. Each invitation has a ⋮ menu with these actions:

- **New link** makes a new invite link, valid for 7 days. The old link stops.
- **Revoke** withdraws the invitation. The page asks you to confirm.

## Devices

The **Devices** panel shows "Pangolin apps and machine clients". Each device is a card that shows:

- The name of the device.
- The status: `online`, `offline`, `blocked` or `archived`.
- The app, and the owner. The owner is the email of the person, or **Machine client**.
- The node that the device relays through, for example `via na1`.

Click a device to open it in the Inspector. The Inspector has these actions:

- **Block** or **Unblock**. For **Block**, the page asks you to confirm.
- **Archive** or **Restore**.
- **Delete**. The page asks you to type the name of the device to confirm.

<img src="../img/access/04-devices.png" alt="The Devices panel with an online device and the node it relays through">

## Roles

Use the names of Pangolin roles in these settings:

- `homegap.roles`, for private services, `auth=sso` and private paths. See the [Label reference](../services/labels.md).
- `cluster.exit_nodes.roles`, for the exits. See [Exit nodes](exit-nodes.md).

The `Admin` role can reach everything.

## API

The page uses these API routes. See [HTTP API](../reference/api.md).

| Route | What it does |
| --- | --- |
| `GET /api/access` | Returns the users, invitations, devices and roles. |
| `POST /api/access/users` | Creates a user, or an invitation. |
| `DELETE /api/access/users/{id}` | Removes a user after 30 seconds. The answer has an undo id. |
| `PUT /api/access/users/{id}/roles` | Sets the roles of a user. |
| `POST /api/access/invitations/{id}/reissue` | Makes a new invite link for an open invitation. |
| `DELETE /api/access/invitations/{id}` | Removes an invitation. |
| `POST /api/access/devices/{id}/{action}` | Does an action on a device: `block`, `unblock`, `archive`, `unarchive` or `delete`. |
