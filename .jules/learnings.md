# Performance Learnings - StreamCast

## Discovery Engine Debouncing
- **Problem:** Frequent updates to the device list during discovery (mDNS/SSDP) caused excessive StateFlow emissions and UI re-renders.
- **Solution:** Implemented a debounced update mechanism in `DiscoveryEngine.kt`.
- **Key Pattern:** Using `discoveryScope?.launch { delay(500); ... }` with `updateJob?.cancel()` to coalesce multiple rapid updates.
- **Benefit:** Reduces CPU usage and UI flicker during the initial discovery phase.
