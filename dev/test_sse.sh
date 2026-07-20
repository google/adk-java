#!/bin/bash

# Test Script for SSE Endpoint
# Author: Sandeep Belgavi
# Date: January 24, 2026

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
SSE_URL="http://localhost:9085/run_sse"
APP_NAME="${APP_NAME:-your-app-name}"
USER_ID="${USER_ID:-test-user}"
SESSION_ID="test-session-$(date +%s)"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}SSE Endpoint Test Script${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Configuration:"
echo "  URL: $SSE_URL"
echo "  App Name: $APP_NAME"
echo "  User ID: $USER_ID"
echo "  Session ID: $SESSION_ID"
echo ""

# Check if server is running
echo -e "${YELLOW}Checking if server is running...${NC}"
if ! curl -s -o /dev/null -w "%{http_code}" http://localhost:9085/run_sse > /dev/null 2>&1; then
    echo -e "${RED}Error: Server does not appear to be running on port 9085${NC}"
    echo "Please start the server first:"
    echo "  cd /Users/sandeep.b/IdeaProjects/voice/adk-java/dev"
    echo "  mvn spring-boot:run"
    exit 1
fi
echo -e "${GREEN}Server is running!${NC}"
echo ""

# Test 1: Basic SSE Request
echo -e "${YELLOW}Test 1: Basic SSE Request${NC}"
echo "----------------------------------------"
curl -N -X POST "$SSE_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"appName\": \"$APP_NAME\",
    \"userId\": \"$USER_ID\",
    \"sessionId\": \"$SESSION_ID\",
    \"newMessage\": {
      \"role\": \"user\",
      \"parts\": [{\"text\": \"Hello, this is a test message\"}]
    },
    \"streaming\": true
  }" 2>&1 | head -20

echo ""
echo ""

# Test 2: SSE with State Delta
echo -e "${YELLOW}Test 2: SSE with State Delta${NC}"
echo "----------------------------------------"
SESSION_ID_2="test-session-$(date +%s)-2"
curl -N -X POST "$SSE_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"appName\": \"$APP_NAME\",
    \"userId\": \"$USER_ID\",
    \"sessionId\": \"$SESSION_ID_2\",
    \"newMessage\": {
      \"role\": \"user\",
      \"parts\": [{\"text\": \"Test with state delta\"}]
    },
    \"streaming\": true,
    \"stateDelta\": {
      \"testKey\": \"testValue\",
      \"config\": {\"setting\": \"test\"}
    }
  }" 2>&1 | head -20

echo ""
echo ""

# Test 3: CORS Preflight
echo -e "${YELLOW}Test 3: CORS Preflight (OPTIONS)${NC}"
echo "----------------------------------------"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X OPTIONS "$SSE_URL" \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: Content-Type")

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}CORS preflight successful (HTTP $HTTP_CODE)${NC}"
else
    echo -e "${RED}CORS preflight failed (HTTP $HTTP_CODE)${NC}"
fi

echo ""
echo ""

# Test 4: Error Case - Missing appName
echo -e "${YELLOW}Test 4: Error Case - Missing appName${NC}"
echo "----------------------------------------"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$SSE_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"userId\": \"$USER_ID\",
    \"sessionId\": \"$SESSION_ID\",
    \"newMessage\": {
      \"role\": \"user\",
      \"parts\": [{\"text\": \"Hello\"}]
    }
  }")

if [ "$HTTP_CODE" = "400" ]; then
    echo -e "${GREEN}Error handling works correctly (HTTP $HTTP_CODE)${NC}"
else
    echo -e "${RED}Unexpected response (HTTP $HTTP_CODE)${NC}"
fi

echo ""
echo ""

# Test 5: Error Case - Missing sessionId
echo -e "${YELLOW}Test 5: Error Case - Missing sessionId${NC}"
echo "----------------------------------------"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$SSE_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"appName\": \"$APP_NAME\",
    \"userId\": \"$USER_ID\",
    \"newMessage\": {
      \"role\": \"user\",
      \"parts\": [{\"text\": \"Hello\"}]
    }
  }")

if [ "$HTTP_CODE" = "400" ]; then
    echo -e "${GREEN}Error handling works correctly (HTTP $HTTP_CODE)${NC}"
else
    echo -e "${RED}Unexpected response (HTTP $HTTP_CODE)${NC}"
fi

echo ""
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}All tests completed!${NC}"
echo -e "${GREEN}========================================${NC}"
