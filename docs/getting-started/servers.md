# Prepare the servers

This page tells you what to create at your VPS provider. It also gives the two ways to give HomeGAP access to the servers.

## Create the servers

Create one VPS for each region. You can use any provider. Each server must have:

- Ubuntu 22.04, 24.04, 26.04 or 26.10.
- An x86_64 or aarch64 CPU.
- A public IPv4 address. IPv6 is optional.

Write down the public IP address, the SSH port and the initial login that the provider gives you. The initial login is a root password or a key.

## The firewall on the node

By default, HomeGAP manages `ufw` on each node (`nodes[].firewall`, default `true`). The firewall opens only these ports:

- SSH, from anywhere or only from the sources in `ssh_allow_from`.
- 80/tcp, 443/tcp and 443/udp.
- 51820/udp and 21820/udp, for WireGuard and Gerbil.
- In cluster mode, `cluster.mesh.port`/udp (default 51821).
- The public ports of raw TCP and UDP services, as their labels set them.

A firewall at the provider level (for example a security group) is outside the control of HomeGAP. It must also open the same ports.

HomeGAP installs Docker on the node if Docker is not there (`install_docker`, default `true`).

## Give HomeGAP access

=== "With the wizard (recommended)"

    Keep the initial root login from your provider, with its password or its key. You do not prepare anything else on the server.

    When the orchestrator runs, continue at [Add a node](add-node.md). The wizard uses the initial login to create the management user and the SSH key.

=== "By hand"

    Create a user with passwordless sudo and a dedicated SSH key for each node. Then add the node to `nodes[]` in the config.

    Run these commands on the homelab, in the HomeGAP folder. Replace the user, the address and the node id with your values.

    1. Create the folder for the keys:
        ```bash
        mkdir -p secrets/ssh
        ```
    2. Create a key for the node:
        ```bash
        ssh-keygen -t ed25519 -N '' -C homegap-na1 -f secrets/ssh/na1
        ```
    3. Make the private key readable only by you:
        ```bash
        chmod 600 secrets/ssh/na1
        ```
    4. Copy the public key to the server:
        ```bash
        ssh-copy-id -i secrets/ssh/na1.pub deploy@203.0.113.10
        ```
    5. Give the user passwordless sudo:
        ```bash
        ssh -t -i secrets/ssh/na1 deploy@203.0.113.10 'echo "deploy ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/homegap >/dev/null && sudo chmod 440 /etc/sudoers.d/homegap'
        ```
    6. Test the login and sudo:
        ```bash
        ssh -i secrets/ssh/na1 -o BatchMode=yes deploy@203.0.113.10 'sudo -n true && echo ok'
        ```
        The command must print `ok` without a prompt.
    7. Add the node to `nodes[]` in `config/config.yml`:
        ```yaml
        nodes:
          - id: na1
            region: NA
            host: 203.0.113.10
            ssh_user: deploy
            ssh_key_file: /secrets/ssh/na1
            location: { latitude: 40.71, longitude: -74.01, name: "New York" }
        ```

    The `ssh_key_file` path is the path inside the orchestrator container. The compose file mounts `./secrets/ssh` at `/secrets/ssh`.

    These rules apply to the entry:

    - The node id must match `^[a-z][a-z0-9-]{0,30}$`. It has lowercase letters, digits and dashes, and it starts with a letter.
    - If `host` is not an IPv4 address, set `public_ipv4`.
    - With `bunny.routing: geolocation` (the default), each node needs a `location` or a known `region` code. The codes are `NA`, `SA`, `EU`, `AF`, `ME`, `AS` and `OC`.

    For all the node keys, see the [Configuration reference](../configuration/reference.md).
