#!/bin/bash

# Load development environment variables and run the API
# Usage: ./run-dev.sh

# Check if .env.development exists
if [ ! -f ".env.development" ]; then
    echo "Error: .env.development file not found"
    echo "Please create .env.development with your development credentials"
    exit 1
fi

# Export environment variables from .env.development
set -a
source .env.development
set +a

echo "Starting API with development Supabase branch..."
echo "Supabase URL: $SUPABASE_API_URL"
echo ""

./gradlew run
