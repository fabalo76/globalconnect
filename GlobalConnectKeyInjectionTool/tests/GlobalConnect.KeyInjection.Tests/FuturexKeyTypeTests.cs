using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class FuturexKeyTypeTests
{
    /// <summary>
    /// Verifies that every selected KTK is ordered before operational keys while
    /// preserving the relative order inside both priority groups.
    /// </summary>
    [Fact]
    public void InjectionPriorityOrdersKtkFirstAndPreservesRemainingOrder()
    {
        FuturexKeyType[] selected =
        [
            FuturexKeyType.Pin,
            FuturexKeyType.DefaultKtk,
            FuturexKeyType.Mac,
            FuturexKeyType.HostVerificationKtk,
            FuturexKeyType.DukptInitial
        ];

        var ordered = selected.OrderBy(keyType => keyType.InjectionPriority()).ToArray();

        Assert.Equal(
        [
            FuturexKeyType.DefaultKtk,
            FuturexKeyType.HostVerificationKtk,
            FuturexKeyType.Pin,
            FuturexKeyType.Mac,
            FuturexKeyType.DukptInitial
        ], ordered);
    }

    /// <summary>
    /// Verifies that applying injection priority without a selected KTK does not
    /// change the operator's existing key order.
    /// </summary>
    [Fact]
    public void InjectionPriorityWithoutKtkPreservesSelectionOrder()
    {
        FuturexKeyType[] selected =
        [
            FuturexKeyType.DukptInitial,
            FuturexKeyType.Pin,
            FuturexKeyType.Mac
        ];

        var ordered = selected.OrderBy(keyType => keyType.InjectionPriority()).ToArray();

        Assert.Equal(selected, ordered);
    }
}
