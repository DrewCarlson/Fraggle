# Signal Setup

!!! note "Work in Progress"
    This documentation is being written.

## Prerequisites

- signal-cli installed and in PATH
- A phone number registered with Signal

## Configuration

Configure Signal in `fraggle.yaml`:

```yaml
fraggle:
  bridges:
    signal:
      enabled: true
      phone: "+1234567890"
      trigger: "@fraggle"
```
