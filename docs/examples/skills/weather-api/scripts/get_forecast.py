#!/usr/bin/env python3
"""Fetch a multi-day weather forecast for a city from OpenWeatherMap."""

import argparse
import os
import sys
from datetime import datetime

import requests


def main(city: str, days: int) -> None:
    api_key = os.environ.get("WEATHER_API_KEY")
    if not api_key:
        print("Error: WEATHER_API_KEY is not configured.")
        print("Run: fraggle skills secrets set weather-api WEATHER_API_KEY")
        sys.exit(1)

    url = "https://api.openweathermap.org/data/2.5/forecast"
    params = {"q": city, "appid": api_key, "units": "metric", "cnt": days * 8}

    try:
        resp = requests.get(url, params=params, timeout=10)
        resp.raise_for_status()
        data = resp.json()

        city_name = data["city"]["name"]
        country = data["city"]["country"]
        print(f"Forecast for {city_name}, {country} ({days} days):")
        print()

        # Group by date.
        by_date: dict[str, list] = {}
        for entry in data["list"]:
            dt = datetime.fromtimestamp(entry["dt"])
            date_key = dt.strftime("%Y-%m-%d")
            by_date.setdefault(date_key, []).append(entry)

        for date_key, entries in list(by_date.items())[:days]:
            temps = [e["main"]["temp"] for e in entries]
            descriptions = {e["weather"][0]["description"] for e in entries}
            print(f"  {date_key}:")
            print(f"    High: {max(temps):.1f}C  Low: {min(temps):.1f}C")
            print(f"    Conditions: {', '.join(sorted(descriptions))}")

    except requests.exceptions.HTTPError as e:
        if e.response is not None and e.response.status_code == 401:
            print("Error: Invalid API key.")
        elif e.response is not None and e.response.status_code == 404:
            print(f"Error: City '{city}' not found.")
        else:
            print(f"Error: {e}")
        sys.exit(1)
    except requests.exceptions.RequestException as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Get weather forecast for a city")
    parser.add_argument("--city", required=True, help="City name (e.g. 'London')")
    parser.add_argument("--days", type=int, default=3, help="Number of days (default: 3)")
    args = parser.parse_args()
    main(args.city, args.days)
