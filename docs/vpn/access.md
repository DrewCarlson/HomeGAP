# Users, machines and devices

HomeGAP holds the users who can sign in, the machines that connect with no user, and their devices. This page tells you how to manage them on the **Access** page of the dashboard and from the command line.

## The three kinds

- A **user** is a person. A user has an email address, a name, one or more roles and a password.
- A **machine** is a device with no user, for example a server. It connects with an id and a secret.
- A **device** is one app on one phone or computer. HomeGAP creates the device at the first sign-in of the app. A user can have 50 devices.

There are no invitations. You create the user and give the password to the person. If you use an OpenID Connect provider, the person signs in there and needs no HomeGAP password. See [Sign-in](sign-in.md).

## The Access page

The page needs `sign_in.url` in the config. Without it, the page shows "Set sign_in.url in the config: it is the address that the apps connect to."

The headline gives the number of users, of devices and of devices that are online. The page has three panels:

- **People** lists each user with the name, the email address, the roles and the number of devices. A user can have the mark `blocked` or `only admin`.
- **Devices** has one card for each device and each machine.
- **App address** shows `sign_in.url`, with a **Copy** button and a QR code under **Show QR**. Give this address to your users.

HomeGAP writes each change that you make here to the reconcile log. The page shows a change at once. A device that connects shows after a maximum of 60 seconds.

<!-- SCREENSHOT: docs/img/access/01-page.png | The Access page with the People panel, the Devices panel and the App address panel -->

### Add a user

1. Click **+ Add user**. The **Add a user** panel opens.
2. Type the email address in **Email**.
3. Under **Roles**, select one or more roles. At the start, the panel selects one role that is not `Admin`.
4. To choose the password, keep **Set a password now** selected and type 12 characters or more in **Password**.
5. To let HomeGAP make the password, clear **Set a password now**.
6. Click **Create user**.
7. If the panel shows a password from HomeGAP, click **Copy password**. Then click **Done**. The panel shows the password only one time.
8. Give the password to the person on a private channel.

<!-- SCREENSHOT: docs/img/access/02-add-user.png | The Add a user panel with an email address, a role and Set a password now selected -->

### Change or remove a user

Click a user in **People**. The Inspector opens with the roles, the devices and the actions of the user.

- To change the roles, select them under **Roles** and click **Save**. A user needs one role or more.
- A user whose roles come from the sign-in provider shows the name of the provider, for example **Authelia**. The Inspector has no role selection for this user. Change the groups of the user at the provider. See [Sign-in](sign-in.md#users-and-roles-from-the-provider).
- **New password** makes a new password and shows it one time. The old password stops.
- **Block** stops the sign-in of the user and disconnects the devices of the user. **Unblock** ends the block.
- **Remove** deletes the user. The devices of the user disconnect, and HomeGAP removes them.

The page asks you to confirm **Block** and **Remove**.

HomeGAP keeps one admin. You cannot remove or block the only user with the role `Admin` who has no block, and you cannot remove that role. The message is "This is the last admin. Make another user an admin first."

### Add a machine

1. Click **+ Add machine**. The **Add a machine** panel opens.
2. Type a name in **Name**, for example `backup server`.
3. Under **Roles**, select one or more roles.
4. Click **Create machine**. The panel shows the **Id** and the **Secret**.
5. Click **Copy** for each value and keep the two values. HomeGAP shows the secret only one time.
6. Click **Done**.

A machine name has 1 to 60 letters, digits, spaces, `.`, `-` and `_`. To connect the machine, see [Connect a machine with olm](pangolin-apps.md#connect-a-machine-with-olm).

### Devices

Each card in **Devices** shows the name of the device, its status, the app and the owner. The owner is the email address of the user, or **Machine client**. While a device is online, the card also shows the node that it connects through, for example `via na1`.

| Status | Meaning |
| --- | --- |
| `online` | The device has a tunnel to the homelab now. |
| `offline` | The device has no tunnel. A machine shows as `offline` before its first connection. |
| `blocked` | The device, the machine or its user has a block. |

Click a card to open the device in the Inspector. The Inspector shows the **Owner**, the **App**, **Last seen**, the **Kind** (`user` or `machine`) and **Relays through**. It has these actions:

- **Block** stops this device. The device loses its tunnel in a few seconds, and the app shows that its access ended. Other devices of the same person continue to work. **Unblock** ends the block.
- **Delete** removes the device. To connect again, the app must sign in again. For a machine, **Delete** removes the machine, and its id and secret stop.

The page asks you to confirm **Block**. For **Delete**, it asks you to type the name of the device.

<!-- SCREENSHOT: docs/img/access/04-devices.png | The Devices panel with an online device, the node it connects through and a machine -->

## Roles

A role is a name. HomeGAP has two roles from the start, and you can name more.

- `Admin` reaches each private service, each exit and each DNS server of the tunnel.
- `Member` is the role of a new user or machine if you select no other role.
- Other roles exist when something names them: a user, a machine, the `homegap.roles` label of a service, `vpn.exit_nodes.roles` or `vpn.tunnel_dns.roles`. The **Access** page then offers them.

A role name has 1 to 40 letters, digits, spaces, `-` and `_`.

The roles of a user or a machine decide what its devices reach:

- A private service with a `homegap.roles` label is for those roles and for `Admin`. See the [Label reference](../services/labels.md).
- A private service with no `homegap.roles` label is for `Admin` only.
- The exits are for `vpn.exit_nodes.roles`, and the DNS servers of the tunnel for `vpn.tunnel_dns.roles`. See [Exits and tunnel DNS](exit-nodes.md).

A change of roles applies to connected devices at once.

## The command line

The two commands talk to the orchestrator, so the orchestrator must run. Run them in the orchestrator container.

### Users

```bash
docker compose exec orchestrator homegap user list
docker compose exec orchestrator homegap user add ada@example.net --name "Ada" --role Admin
docker compose exec orchestrator homegap user password ada@example.net
docker compose exec orchestrator homegap user block ada@example.net
docker compose exec orchestrator homegap user unblock ada@example.net
docker compose exec orchestrator homegap user remove ada@example.net
```

- `list` prints the email address, the roles and `active` or `blocked` for each user.
- `add` creates the user and prints a new password from HomeGAP. Repeat `--role` for more roles. With no `--role`, the user gets `Member`.
- `password` gives the user a new password and prints it.

HomeGAP does not keep a password that it prints. Give it to the user immediately.

On a new deployment, create your own user first, with `--role Admin`.

### Machines

```bash
docker compose exec orchestrator homegap machine list
docker compose exec orchestrator homegap machine add backup --role Member
docker compose exec orchestrator homegap machine remove backup
```

- `list` prints the name, the id and the roles of each machine.
- `add` creates the machine and prints its `id` and its `secret`. HomeGAP does not keep the secret.
- `remove` deletes the machine. A connected machine loses its tunnel.

To change the roles of a user, or to block or delete a device, use the **Access** page. For all commands, see [Command line](../reference/cli.md).

## API

The **Access** page uses these routes. See [HTTP API](../reference/api.md).

| Route | What it does |
| --- | --- |
| `GET /api/access` | Returns the address of the sign-in, the roles, the users and the devices. |
| `POST /api/access/users` | Creates a user. The answer has the password if the request has none. |
| `PUT /api/access/users/{id}/roles` | Sets the roles of a user. |
| `POST /api/access/users/{id}/password` | Gives a user a new password. The answer has it. |
| `POST /api/access/users/{id}/{action}` | Does `block` or `unblock` on a user. |
| `DELETE /api/access/users/{id}` | Removes a user. |
| `POST /api/access/machines` | Creates a machine. The answer has its id and its secret. |
| `POST /api/access/devices/{id}/{action}` | Does `block`, `unblock` or `delete` on a device or a machine. |
