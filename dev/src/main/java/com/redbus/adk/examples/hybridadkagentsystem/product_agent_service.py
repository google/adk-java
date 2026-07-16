"""
Java ADK agent that proxies user input to a Python agent running at http://127.0.0.1:8000/
and returns the response.

This agent acts as a client to an external ADK Python agent service.

Author: Sandeep Belgavi
Version: 1.0
Date: 2025-07-04
"""

import logging
import re
import json
import asyncio
from typing import Dict, Any, List, Union

import clickhouse_connect # External dependency for the database tool
from google.adk.agents import LlmAgent, AgentResponse
from google.adk.models.lite_llm import LiteLlm
from pydantic import BaseModel, Field

# --- FastAPI for exposing the agent as a service ---
from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse
from google.adk.sessions.in_memory_session_store import InMemorySessionStore
from google.adk.api.protocol import AgentRequest as AdkAgentRequest, AgentResponse as AdkAgentResponse

# Define the response model for the tool
class ProductDetailsResponse(BaseModel):
    status: str # 'success', 'not_found', 'error'
    message: str # Detailed message or product information

# --- Configure Logging ---
logging.basicConfig(level=logging.INFO) # Set to INFO for cleaner service logs by default
logger = logging.getLogger(__name__)

# --- ClickHouse Tool Function ---
def ProductDetailsTool(product_id: str) -> ProductDetailsResponse:
    """
    Fetches details for a specific product ID from the ProductCatalog database.
    Designed to be called by an LLM agent.

    Args:
        product_id: The product ID to fetch details for. This should be a numeric string.

    Returns:
        ProductDetailsResponse: An object containing the status of the operation
                                ('success', 'not_found', 'error') and a message.
    """
    logger.info(f"ProductDetailsTool called with product_id: {product_id}")
    try:
        if not product_id.isdigit():
            logger.warning(f"Invalid product ID received: '{product_id}'. Must be numeric.")
            return ProductDetailsResponse(
                status="error",
                message=f"Invalid product ID '{product_id}'. Product ID must be numeric."
            )

        # IMPORTANT: Replace with your ClickHouse connection details or environment variables
        # Using generic placeholders for example. Ensure accessibility from where this script runs.
        client = clickhouse_connect.get_client(
            host='127.0.0.1', # << CONFIGURE THIS TO YOUR CLICKHOUSE INSTANCE IP/HOSTNAME
            port=8123,      # Default ClickHouse HTTP(s) port
            username='db_user',
            password='db_password',
            secure=False    # Set to True if using SSL/TLS
        )

        # SQL query to fetch product name, description, and inventory for the given product ID
        query = """
            SELECT p.ProductName, p.Description, i.StockLevel
            FROM ProductCatalog.Products p
            LEFT JOIN ProductCatalog.Inventory i ON p.ProductId = i.ProductId
            WHERE p.ProductId = %(product_id)s
            LIMIT 1
        """

        logger.debug(f"Executing ClickHouse query for product_id: {product_id}")
        result = client.query(query, parameters={"product_id": int(product_id)}).result_rows

        if result:
            product_name, description, stock_level = result[0]
            message = (f"Product ID {product_id}: '{product_name}'. "
                       f"Description: '{description}'. Current Stock: {stock_level}.")
            logger.info(f"Product found for ID {product_id}. Details: '{message[:100]}...'")
            return ProductDetailsResponse(
                status="success",
                message=message
            )
        else:
            logger.info(f"No product found for ID {product_id}.")
            return ProductDetailsResponse(
                status="not_found",
                message=f"No product found with ID {product_id}. Would you like to check another ID?"
            )

    except Exception as e:
        logger.error(f"Error fetching product from ClickHouse for ID {product_id}: {e}", exc_info=True)
        return ProductDetailsResponse(
            status="error",
            message=f"An error occurred while fetching details for Product ID {product_id}: {str(e)}. Please try again later."
        )

# --- Create the LLM Agent ---
# We'll put this agent into a dictionary for easy lookup by app_name
ADK_AGENTS = {}

Product_Agent = LlmAgent(
    name="Product_Agent", # Renamed agent name for product domain
    model=LiteLlm(model="ollama_chat/llama3.2:latest"), # Ensure this model is accessible and configured correctly
    tools=[ProductDetailsTool], # Register the new tool with the agent
    instruction="""
    You are a helpful product information assistant. Your primary goal is to fetch and present product details for a given product ID, and then conclude your response for the current turn.

    **Strict Steps:**
    1.  **Extract Product ID:** Carefully identify and extract the numerical product ID from the user's message.
    2.  **Call Tool:** Use the `ProductDetailsTool` function with the extracted product ID.
    3.  **Process Tool Result and Respond:**
        * If the tool returns a `status: "success"`, present the product details clearly and politely. After presenting the details, **your task for this turn is complete, and you should provide a final, helpful response to the user.**
        * If the tool returns a `status: "not_found"`, inform the user that no product was found for that ID, and politely offer to check another ID. **Then, your task for this turn is complete.**
        * If the tool returns an `status: "error"`, acknowledge the error gracefully and suggest they try again later or with a different product ID. **Then, your task for this turn is complete.**
    4.  **Handle Missing Product ID:** If the user does not provide a product ID in their initial message, politely ask them to specify one. Do NOT try to call the tool without a product ID.

    **Important:** Once you have provided a response based on the `ProductDetailsTool`'s output (whether success, not found, or error), consider the current turn's objective achieved. **Do not re-prompt for the same product ID or re-call the tool for the same request.** Focus on providing a single, conclusive answer for each user query.
    """
)
ADK_AGENTS[Product_Agent.name] = Product_Agent

# --- FastAPI Application ---
app = FastAPI(title="ADK Python Product Agent Service")

# Using an in-memory session store for simplicity.
# For production, consider persistent stores (e.g., Redis, database).
session_store = InMemorySessionStore()

@app.post("/apps/{app_name}/users/{user_id}/sessions/{session_id}")
async def create_session(app_name: str, user_id: str, session_id: str, state: Dict[str, Any]):
    """
    Creates or updates a session for a given app, user, and session ID.
    """
    try:
        session_store.create_session(app_name, user_id, session_id, state)
        logger.info(f"Session created/updated: app={app_name}, user={user_id}, session={session_id}")
        return {"status": "success", "message": "Session created/updated successfully"}
    except Exception as e:
        logger.error(f"Error creating session: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to create session: {e}"
        )

@app.post("/run")
async def run_agent(request: AdkAgentRequest):
    """
    Processes an incoming message for an ADK agent, retrieves or creates a session,
    and returns the agent's response.
    """
    app_name = request.app_name
    user_id = request.user_id
    session_id = request.session_id
    new_message = request.new_message

    if app_name not in ADK_AGENTS:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Agent '{app_name}' not found."
        )

    agent = ADK_AGENTS[app_name]

    # Retrieve or create session
    try:
        session = session_store.get_session(app_name, user_id, session_id)
        if session is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Session not found. Please create a session using /apps/{app_name}/users/{user_id}/sessions/{session_id} endpoint."
            )
    except Exception as e:
        logger.error(f"Error getting session: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to retrieve session: {e}"
        )

    try:
        logger.info(f"Running agent '{app_name}' for user '{user_id}' session '{session_id}' with message: {new_message.parts[0].text if new_message.parts else ''}")

        agent_response: AgentResponse = await agent.generate_response(new_message.parts[0].text, session=session)

        adk_response_list: List[AdkAgentResponse] = agent_response.to_protocol()

        return JSONResponse(content=[event.model_dump() for event in adk_response_list])

    except Exception as e:
        logger.error(f"Error during agent execution: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"An error occurred during agent execution: {e}"
        )

if __name__ == "__main__":
    import uvicorn
    # Run the FastAPI application
    uvicorn.run(app, host="0.0.0.0", port=8000)