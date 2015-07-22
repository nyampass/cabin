# cabin

FIXME

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Running

To start a web server for the application, run:

    lein ring server

## API

### promotion

- Request format
```
{
 "type": "promote",
 "from": "<peer id>",
 "password": "<password>",
 ["custom-name": "<custom name>",]
 ["custom-name-password": "<custom name password>"]
}
```

- Response format
```
{
 "type": "promote",
 "status": ("ok"|"error"),
 ["cause": "<error cause>"]
}
```

### demotion

- Request format
```
{
 "type": "demote",
 "from": "<peer id>"
}
```

- Response format
```
{
 "type": "demote",
 "status": ("ok"|"error"),
 ["cause": "<error cause>"]
}
```

## License

Copyright © 2015 Nyampass Co. Ltd.

Distributed under the Eclipse Public License version 1.0.
