# Add a node

The add-node wizard takes a fresh VPS from the initial login of your provider to a provisioned node. This page tells you how to use the wizard, and how to add a node by hand.

## Before you start

The wizard writes the node to the config file. Thus `dashboard.allow_config_edits` must be `true` (the default), and the config mount of the orchestrator must be writable. Before each write, HomeGAP saves a backup of the file as `<config>.bak-<millis>`.

Setup runs on the orchestrator as a background job. You can leave the page while the job runs.

## How setup protects your access

Setup logs in one time with the initial credentials, and it does every step through that one connection. If the server has a login blocker (fail2ban, sshguard or CrowdSec), setup first exempts the address of the orchestrator, as the server sees it. It also removes any current ban on that address.

Setup proves each change before it keeps the change:

- Before it hardens SSH, setup checks the key login and the passwordless sudo of the new user over a fresh connection.
- Setup writes the SSH settings to `/etc/ssh/sshd_config.d/01-homegap.conf`, which comes before the drop-in file of cloud-init. It checks the syntax of the file.
- Setup checks the effective SSH values with `sshd -T -C` for root, the login user and the management user, from the address of the orchestrator. Thus it finds a `Match` block that overrides the settings.
- After the change, a fresh key login must work. If it does not work, setup restores the previous settings.
- Before a change to the SSH settings or the firewall, setup arms a timer on the node. The timer reverts the change after two minutes. Setup disarms it only after a fresh login works.

If the orchestrator loses the connection to the node during a change, the node reverts the change by itself.

The password is for the setup job only. HomeGAP never writes it to disk or to the config. The password goes to `ssh` through `SSH_ASKPASS`, and to `sudo` on its standard input.

## Use the wizard

### Open the wizard

1. On the dashboard, open **Nodes**.
2. Click **Add node**. The page shows "Set up a fresh server from its initial login and provision it".
<!-- SCREENSHOT: docs/img/add-node/01-open-wizard.png | the Nodes page with the Add node button -->

### 1. Server

1. In **Node id**, type the id of the node, for example `eu1`. Use lowercase letters, digits and dashes. Start with a letter.
2. In **Public IP or host name**, type the address of the server.
3. In **SSH port**, type the SSH port. The default is `22`.
4. Set the location. Search the known data-center cities (for example "dallas", "mumbai" or "hetzner"), or click a point in **Pick on the map**.
5. Optional: correct **Region code**, **Latitude**, **Longitude** or **Place name**.
6. Optional: type the **Provider**.
7. Click **Continue**.

When you pick a point on the map, the nearest known city within 50 km gives the point its name.

<!-- SCREENSHOT: docs/img/add-node/02-server.png | the Server step with the node id, the address and a location from the search -->

### 2. Initial login

1. In **User**, type `root` or another user that can use sudo.
2. Select **Password** or **Private key**.
3. For **Password**, type the password.
4. For **Private key**, type the path of the key file in **Private key on the orchestrator**.
5. Click **Test connection**.
6. Compare the **Host key** fingerprint with the fingerprint in the console of your provider.
7. Click **Continue**.

You must test the connection before you can continue. The test checks the login and shows this information:

- The OS, as `Connected to <os> <version> (<arch>)`.
- **Root access**: how the user gets root access.
- **Docker**: if Docker is installed.
- **Host key**: the fingerprint of the host key of the server.
- **Orchestrator seen from**: the address of the orchestrator, as the node sees it.

If the host key is different from the last connection, the test reports it. Make sure that the server has a new install. Then click **Forget the old host key and retry**.

<!-- SCREENSHOT: docs/img/add-node/03-initial-login.png | the Initial login step after a successful connection test -->

### 3. Access

The **Management user** is "The account HomeGAP manages the node with."

1. Keep **Create a dedicated user** selected. The default **User name** is `homegap`. This user has passwordless sudo and logs in over SSH with a key only.
2. Optional: select **Add it to the docker group**.
3. Under **SSH key**, select **Generate a new key**, **Paste a private key** or **Use a key file on the orchestrator**.
4. Click **Generate key** or **Use this key**. The page shows "Key ready" and the fingerprint.
5. Optional: under **SSH hardening**, select **Disable root login**. This option needs a management user other than root.
6. Optional: select **Disable password login**. Then SSH accepts keys only, for every user.
7. Click **Continue**.

A generated key is an ed25519 key. HomeGAP keeps it in `<data dir>/ssh/` on the orchestrator.

HomeGAP uses sudo only for system changes: packages, the firewall, WireGuard and sysctls. The files under the install directory (`/opt/homegap` by default) belong to the management user. If the user is in the `docker` group, HomeGAP also runs Docker Compose as this user, so `docker compose` works there without sudo. The data directories of the cluster member stay with their containers.

<!-- SCREENSHOT: docs/img/add-node/04-access.png | the Access step with a dedicated user, a generated key and both options under SSH hardening -->

### 4. Firewall

1. Keep **Let HomeGAP manage the firewall** selected. HomeGAP keeps the rules in sync on each provisioning pass.
2. Under **SSH from**, select **Anywhere** or **Only these sources**.
3. For **Only these sources**, check the list in **Allowed sources**. The wizard fills it with the address from the connection test.
4. Click **Continue**.

The page shows the rules:

- SSH on its port (tcp), from anywhere or from the sources in the list.
- HTTP 80/tcp, HTTPS 443/tcp and 443/udp.
- WireGuard 51820/udp and 21820/udp.
- The ports of raw TCP and UDP services, as their labels set them.

!!! danger "Warning"
    This risk applies to **Only these sources**. If the public IP address of the orchestrator changes to an address outside the list, HomeGAP loses SSH access to the node. This occurs with a residential IP address, a new ISP or a homelab in a new location. Provisioning and health checks for that node then fail until you correct the rule.

    Use a stable address range. Or open SSH to anywhere and disable password login.

    To recover, log in through the web console of your provider. Run `ufw allow <ssh port>/tcp`. Then update `ssh_allow_from` in the config. The next pass replaces the rules.

<!-- SCREENSHOT: docs/img/add-node/05-firewall.png | the Firewall step with the list of rules -->

### 5. Review & run

1. Examine the summary.
2. Click **Set up and add node**. The page shows the progress of each setup step.
3. When the page shows "Done. Provisioning starts on the next reconcile pass.", click **Go to nodes**.

<!-- SCREENSHOT: docs/img/add-node/06-review.png | the Review & run step with the summary -->
<!-- SCREENSHOT: docs/img/add-node/07-done.png | the finished setup job with the Go to nodes button -->

## If setup fails

A failed job shows the step with the failure and the reason. Correct the cause, then click **Retry**.

You can run setup again safely. The completed steps are idempotent. If an earlier run already disabled password login, setup continues with the key of the node.

## After the wizard

Provisioning runs in the background. The first install takes a few minutes for Docker, the firewall and the Pangolin stack. During this time, the node shows `provisioning…` on the **Nodes** page.

If the first provisioning fails, HomeGAP tries again after five minutes. To try again at once, click **Re-provision**.

<!-- SCREENSHOT: docs/img/add-node/08-provisioning.png | the Nodes page with the new node in the provisioning… state -->

## Add a node by hand

You can also prepare the server yourself and add the node to `nodes[]` in the config. See the "By hand" tab in [Prepare the servers](servers.md#give-homegap-access).
