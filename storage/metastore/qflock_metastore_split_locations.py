import sys
import glob
import json
import subprocess
import functools
import os
import time

from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol
from thrift.server import TServer
from thrift.server import TNonblockingServer
from thrift.protocol.THeaderProtocol import THeaderProtocolFactory

import pyarrow.parquet
import pyarrow.fs

my_path = os.path.dirname(os.path.realpath(__file__))
sys.path.append(my_path + '/pymetastore')

from hive_metastore import ThriftHiveMetastore
from hive_metastore import ttypes


def get_docker_ip(docker_name: str):
    result = subprocess.run('docker network inspect qflock-net'.split(' '), stdout=subprocess.PIPE)
    d = json.loads(result.stdout)

    for c in d[0]['Containers'].values():
        if c['Name'] == docker_name:
            addr = c['IPv4Address'].split('/')[0]
            with open('host_aliases', 'w') as f:
                f.write(f'{docker_name} {addr}')

            os.environ.putenv('HOSTALIASES', 'host_aliases')
            os.environ['HOSTALIASES'] = 'host_aliases'
            return addr

    return None


def get_storage_size(location: str):
    storage_size = 0
    fs, path = pyarrow.fs.FileSystem.from_uri(table.sd.location)
    file_info = fs.get_file_info(pyarrow.fs.FileSelector(path))
    [storage_size := storage_size + f.size for f in file_info if f.is_file]

    return storage_size


if __name__ == '__main__':
    storage_ip = get_docker_ip('qflock-storage-dc1')
    client_transport = TSocket.TSocket(storage_ip, 9083)
    client_transport = TTransport.TBufferedTransport(client_transport)
    client_protocol = TBinaryProtocol.TBinaryProtocol(client_transport)
    client = ThriftHiveMetastore.Client(client_protocol)

    while not client_transport.isOpen():
        try:
            client_transport.open()
        except BaseException as ex:
            print('Metastore is not ready. Retry in 1 sec.')
            time.sleep(1)

    catalogs = client.get_catalogs()
    print(catalogs)

    databases = client.get_all_databases()
    print(databases)

    db_name = 'tpcds'
    tpcds = client.get_database(db_name)
    print(tpcds)

    tables = client.get_all_tables(db_name)
    print(tables)

    for table_name in tables:
        table = client.get_table(db_name, table_name)
        print(table.sd.location, table.sd.parameters['qflock.storage_size'])

    client_transport.close()




