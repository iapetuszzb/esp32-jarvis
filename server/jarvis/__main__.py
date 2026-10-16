import os
import uvicorn
from .app import create_app

if __name__ == '__main__':
    uvicorn.run(create_app(), host=os.getenv('JARVIS_BIND', '127.0.0.1'),
                port=int(os.getenv('JARVIS_PORT', '8000')), ws_max_size=65536,
                ws_max_queue=4, timeout_keep_alive=10, access_log=False)
