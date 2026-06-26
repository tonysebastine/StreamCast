## 2024-05-23 - [Debounced Discovery Updates]
**Learning:** Discovery-heavy apps often suffer from "update storms" where multiple protocols find the same device. Debouncing at the engine level is more efficient than at the UI level as it avoids redundant sorting and list allocations.
**Action:** Always check if frequent network-driven updates can be debounced or batched before they hit the UI state flow.
