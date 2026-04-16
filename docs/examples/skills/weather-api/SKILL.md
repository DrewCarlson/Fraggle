---
name: weather-api
description: Fetch current weather and forecasts for any city. Use when the user asks about weather, temperature, or forecasts.
license: MIT
env: ['WEATHER_API_KEY']
---

# Weather API

Provides current weather data and forecasts via OpenWeatherMap.

## Setup

This skill requires a free API key from [OpenWeatherMap](https://openweathermap.org/api).

```bash
fraggle skills secrets set weather-api WEATHER_API_KEY
```

## Available Scripts

### Get Current Weather

```bash
python scripts/get_weather.py --city "London"
```

Returns current temperature, conditions, humidity, and wind speed.

### Get Forecast

```bash
python scripts/get_forecast.py --city "London" --days 3
```

Returns a multi-day forecast with high/low temperatures and conditions.

## Usage Notes

- City names are case-insensitive.
- Use `execute_command` with `skill="weather-api"` to run scripts. The API key is injected automatically.
- All temperatures are returned in Celsius.
