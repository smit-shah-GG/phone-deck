"""Set the deck login PIN: python -m app.set_pin"""

import getpass

from . import config

if __name__ == "__main__":
    p1 = getpass.getpass("New deck PIN: ")
    p2 = getpass.getpass("Confirm PIN: ")
    if p1 != p2 or not p1:
        raise SystemExit("PINs empty or do not match.")
    config.set_pin(p1)
    print("PIN set.")
