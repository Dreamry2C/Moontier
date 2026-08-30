use std::{fs, path::PathBuf};

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use easytier::{
    common::config::{ConfigLoader, TomlConfigLoader},
    launcher::NetworkConfig,
    proto::{
        api::manage::{
            CollectNetworkInfoRequest, DeleteNetworkInstanceRequest,
            ListNetworkInstanceMetaRequest, ListNetworkInstanceRequest,
            RunNetworkInstanceRequest, WebClientService, WebClientServiceClientFactory,
        },
        rpc_impl::standalone::StandAloneClient,
        rpc_types::controller::BaseController,
    },
    tunnel::tcp::TcpTunnelConnector,
};
use serde_json::json;

#[derive(Parser)]
#[command(name = "moontier-root-manager")]
struct Cli {
    #[arg(short = 'p', long, default_value = "127.0.0.1:14999")]
    rpc_portal: String,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    Run { config: PathBuf },
    Delete { instance_id: uuid::Uuid },
    List,
    Collect,
    Snapshot,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let portal = if cli.rpc_portal.contains("://") {
        cli.rpc_portal
    } else {
        format!("tcp://{}", cli.rpc_portal)
    };
    let mut rpc = StandAloneClient::new(TcpTunnelConnector::new(
        portal.parse().context("invalid RPC portal")?,
    ));
    let client = rpc
        .scoped_client::<WebClientServiceClientFactory<BaseController>>(String::new())
        .await
        .context("failed to connect to EasyTier manager RPC")?;

    match cli.command {
        Command::Run { config } => {
            let text = fs::read_to_string(&config)
                .with_context(|| format!("failed to read {}", config.display()))?;
            let toml = TomlConfigLoader::new_from_str(&text).context("invalid EasyTier TOML")?;
            let instance_id = toml.get_id();
            let config = NetworkConfig::new_from_config(&toml)
                .context("failed to convert EasyTier TOML")?;
            let response = client
                .run_network_instance(
                    BaseController::default(),
                    RunNetworkInstanceRequest {
                        inst_id: Some(instance_id.into()),
                        config: Some(config),
                        overwrite: true,
                        source: 1,
                    },
                )
                .await?;
            let id = response
                .inst_id
                .map(uuid::Uuid::from)
                .unwrap_or(instance_id);
            println!("{}", json!({ "instance_id": id.to_string() }));
        }
        Command::Delete { instance_id } => {
            let response = client
                .delete_network_instance(
                    BaseController::default(),
                    DeleteNetworkInstanceRequest {
                        inst_ids: vec![instance_id.into()],
                    },
                )
                .await?;
            let remaining = response
                .remain_inst_ids
                .into_iter()
                .map(uuid::Uuid::from)
                .map(|id| id.to_string())
                .collect::<Vec<_>>();
            println!("{}", json!({ "remaining_instance_ids": remaining }));
        }
        Command::List => {
            let instances = client
                .list_network_instance(
                    BaseController::default(),
                    ListNetworkInstanceRequest {},
                )
                .await?
                .inst_ids
                .into_iter()
                .map(uuid::Uuid::from)
                .collect::<Vec<_>>();
            let metas = client
                .list_network_instance_meta(
                    BaseController::default(),
                    ListNetworkInstanceMetaRequest {
                        inst_ids: instances.iter().copied().map(Into::into).collect(),
                    },
                )
                .await?
                .metas
                .into_iter()
                .map(|meta| {
                    json!({
                        "instance_id": meta.inst_id.map(uuid::Uuid::from).map(|id| id.to_string()).unwrap_or_default(),
                        "instance_name": meta.instance_name,
                        "network_name": meta.network_name,
                        "config_permission": meta.config_permission,
                        "source": meta.source,
                    })
                })
                .collect::<Vec<_>>();
            println!(
                "{}",
                json!({
                    "instance_ids": instances.into_iter().map(|id| id.to_string()).collect::<Vec<_>>(),
                    "metas": metas,
                })
            );
        }
        Command::Collect => {
            let response = client
                .collect_network_info(
                    BaseController::default(),
                    CollectNetworkInfoRequest { inst_ids: Vec::new() },
                )
                .await?;
            println!("{}", serde_json::to_string(&response)?);
        }
        Command::Snapshot => {
            let instances = client
                .list_network_instance(
                    BaseController::default(),
                    ListNetworkInstanceRequest {},
                )
                .await?
                .inst_ids
                .into_iter()
                .map(uuid::Uuid::from)
                .collect::<Vec<_>>();
            let metas = client
                .list_network_instance_meta(
                    BaseController::default(),
                    ListNetworkInstanceMetaRequest {
                        inst_ids: instances.iter().copied().map(Into::into).collect(),
                    },
                )
                .await?
                .metas
                .into_iter()
                .map(|meta| {
                    json!({
                        "instance_id": meta.inst_id.map(uuid::Uuid::from).map(|id| id.to_string()).unwrap_or_default(),
                        "instance_name": meta.instance_name,
                        "network_name": meta.network_name,
                        "config_permission": meta.config_permission,
                        "source": meta.source,
                    })
                })
                .collect::<Vec<_>>();
            let running = client
                .collect_network_info(
                    BaseController::default(),
                    CollectNetworkInfoRequest { inst_ids: Vec::new() },
                )
                .await?;
            let mut running_map = serde_json::Map::new();
            if let Some(info_map) = running.info {
                for (instance_id, info) in info_map.map {
                    let my_node_info = info.my_node_info.map(|node| {
                        let virtual_ipv4 = node.virtual_ipv4.map(|ipv4| {
                            json!({
                                "address": ipv4.address.map(|address| json!({ "addr": address.addr })),
                                "network_length": ipv4.network_length,
                            })
                        });
                        json!({
                            "hostname": node.hostname,
                            "virtual_ipv4": virtual_ipv4,
                        })
                    });
                    let routes = info
                        .routes
                        .into_iter()
                        .map(|route| {
                            let ipv4_addr = route.ipv4_addr.map(|ipv4| {
                                json!({
                                    "address": ipv4.address.map(|address| json!({ "addr": address.addr })),
                                    "network_length": ipv4.network_length,
                                })
                            });
                            json!({
                                "hostname": route.hostname,
                                "ipv4_addr": ipv4_addr,
                                "cost": route.cost,
                                "path_latency": route.path_latency,
                                "feature_flag": {
                                    "is_public_server": route.feature_flag.map(|flags| flags.is_public_server).unwrap_or(false),
                                },
                            })
                        })
                        .collect::<Vec<_>>();
                    running_map.insert(
                        instance_id,
                        json!({
                            "my_node_info": my_node_info,
                            "routes": routes,
                            "events": info.events.into_iter().take(40).map(summarize_event).collect::<Vec<_>>(),
                            "running": info.running,
                            "error_msg": info.error_msg,
                        }),
                    );
                }
            }
            println!(
                "{}",
                json!({
                    "instance_ids": instances.into_iter().map(|id| id.to_string()).collect::<Vec<_>>(),
                    "metas": metas,
                    "running": { "info": { "map": running_map } },
                })
            );
        }
    }

    Ok(())
}

fn summarize_event(raw: String) -> String {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(&raw) else {
        return raw;
    };
    let timestamp = value
        .get("time")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default();
    let event_name = value
        .get("event")
        .and_then(serde_json::Value::as_object)
        .and_then(|event| event.keys().next())
        .map(String::as_str)
        .unwrap_or("Event");
    if timestamp.is_empty() {
        event_name.to_string()
    } else {
        format!("{} {}", timestamp, event_name)
    }
}
