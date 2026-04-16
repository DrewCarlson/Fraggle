#!/usr/bin/env python3
"""Fetch current weather for a city from OpenWeatherMap."""

import argparse
import json
import os
import sys

import requests


def main(city: str) -> None:
    api_key = os.environ.get("WEATHER_API_KEY")
    if not api_key:
        print("Error: WEATHER_API_KEY is not configured.")
        print("Run: fraggle skills secrets set weather-api WEATHER_API_KEY")
        sys.exit(1)

    url = "https://api.openweathermap.org/data/2.5/weather"
    params = {"q": city, "appid": api_key, "units": "metric"}

    try:
        resp = requests.get(url, params=params, timeout=10)
        resp.raise_for_status()
        data = resp.json()

        temp = data["main"]["temp"]
        feels_like = data["main"]["feels_like"]
        humidity = data["main"]["humidity"]
        description = data["weather"][0]["description"]
        wind_speed = data["wind"]["speed"]
        city_name = data["name"]
        country = data["sys"]["country"]

        print(f"Weather for {city_name}, {country}:")
        print(f"  Temperature: {temp:.1f}C (feels like {feels_like:.1f}C)")
        print(f"  Conditions:  {description}")
        print(f"  Humidity:    {humidity}%")
        print(f"  Wind:        {wind_speed} m/s")

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
    parser = argparse.ArgumentParser(description="Get current weather for a city")
    parser.add_argument("--city", required=True, help="City name (e.g. 'London')")
    args = parser.parse_args()
    main(args.city)
