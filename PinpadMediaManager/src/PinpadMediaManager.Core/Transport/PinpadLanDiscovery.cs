using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace PinpadMediaManager.Core.Transport;

public sealed record DiscoveredPinpad(
    string SerialNumber,
    string Model,
    string Address,
    int TcpPort)
{
    public override string ToString() =>
        $"{SerialNumber} — {Model} — {Address}:{TcpPort}";
}

public static class PinpadLanDiscovery
{
    public const int DiscoveryPort = 39_100;
    public const string DiscoveryRequest = "GLOBALCONNECT_PINPAD_DISCOVER_V1";
    public const string DiscoveryProtocol = "globalconnect-pinpad-discovery";

    public static async Task<IReadOnlyList<DiscoveredPinpad>> DiscoverAsync(
        TimeSpan? duration = null,
        CancellationToken cancellationToken = default)
    {
        using var client = new UdpClient(AddressFamily.InterNetwork);
        client.EnableBroadcast = true;
        client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        DisableWindowsUdpConnectionReset(client.Client);
        client.Client.Bind(new IPEndPoint(IPAddress.Any, 0));

        var request = Encoding.UTF8.GetBytes(DiscoveryRequest);
        var requestSent = false;
        foreach (var endpoint in BroadcastEndpoints())
        {
            try
            {
                await client.SendAsync(request, endpoint, cancellationToken);
                requestSent = true;
            }
            catch (SocketException)
            {
                // Another active adapter may still be able to reach the PINPAD LAN.
            }
        }
        if (!requestSent)
        {
            throw new InvalidOperationException("No active network adapter could send the PINPAD discovery request.");
        }

        var found = new Dictionary<string, DiscoveredPinpad>(StringComparer.OrdinalIgnoreCase);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(duration ?? TimeSpan.FromSeconds(2));
        while (!timeout.IsCancellationRequested)
        {
            try
            {
                var response = await client.ReceiveAsync(timeout.Token);
                var device = ParseResponse(response.Buffer, response.RemoteEndPoint.Address);
                if (device is not null)
                {
                    found[$"{device.SerialNumber}|{device.Address}|{device.TcpPort}"] = device;
                }
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (SocketException error) when (error.SocketErrorCode == SocketError.ConnectionReset)
            {
                // Windows reports an ICMP "port unreachable" from any broadcast
                // target as WSAECONNRESET on the shared UDP receive socket.
                // It does not mean another adapter or PINPAD cannot still reply.
                continue;
            }
            catch (JsonException)
            {
                // Ignore unrelated UDP traffic on the discovery response socket.
            }
        }

        return found.Values
            .OrderBy(value => value.SerialNumber, StringComparer.OrdinalIgnoreCase)
            .ThenBy(value => value.Address, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    public static DiscoveredPinpad? ParseResponse(ReadOnlyMemory<byte> response, IPAddress senderAddress)
    {
        using var document = JsonDocument.Parse(response);
        var root = document.RootElement;
        if (!root.TryGetProperty("protocol", out var protocol) ||
            !string.Equals(protocol.GetString(), DiscoveryProtocol, StringComparison.Ordinal))
        {
            return null;
        }

        var serial = root.TryGetProperty("serialNumber", out var serialValue)
            ? serialValue.GetString()?.Trim() ?? ""
            : "";
        var model = root.TryGetProperty("model", out var modelValue)
            ? modelValue.GetString()?.Trim() ?? ""
            : "";
        var address = root.TryGetProperty("address", out var addressValue)
            ? addressValue.GetString()?.Trim() ?? ""
            : "";
        if (!IPAddress.TryParse(address, out var parsedAddress) ||
            parsedAddress.AddressFamily != AddressFamily.InterNetwork)
        {
            address = senderAddress.ToString();
        }

        var tcpPort = root.TryGetProperty("tcpPort", out var portValue) && portValue.TryGetInt32(out var port)
            ? port
            : 0;
        if (tcpPort is < 1 or > 65_535)
        {
            return null;
        }

        return new DiscoveredPinpad(serial, model, address, tcpPort);
    }

    private static IEnumerable<IPEndPoint> BroadcastEndpoints()
    {
        var addresses = new HashSet<IPAddress> { IPAddress.Broadcast };
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up ||
                adapter.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            foreach (var unicast in adapter.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork ||
                    unicast.IPv4Mask is null)
                {
                    continue;
                }

                var address = unicast.Address.GetAddressBytes();
                var mask = unicast.IPv4Mask.GetAddressBytes();
                var broadcast = new byte[4];
                for (var index = 0; index < broadcast.Length; index++)
                {
                    broadcast[index] = (byte)(address[index] | ~mask[index]);
                }

                addresses.Add(new IPAddress(broadcast));
            }
        }

        return addresses.Select(address => new IPEndPoint(address, DiscoveryPort));
    }

    private static void DisableWindowsUdpConnectionReset(Socket socket)
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        try
        {
            socket.IOControl(
                SioUdpConnectionReset,
                [0, 0, 0, 0],
                null);
        }
        catch (SocketException)
        {
            // The receive-loop guard above still handles WSAECONNRESET if this
            // control code is unavailable on a particular Windows build.
        }
    }

    private const int SioUdpConnectionReset = unchecked((int)0x9800000C);
}
